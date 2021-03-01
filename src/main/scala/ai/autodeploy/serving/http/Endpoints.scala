/*
 * Copyright (c) 2019-2020 AutoDeployAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.autodeploy.serving.http

import ai.autodeploy.serving.deploy.ModelManager
import ai.autodeploy.serving.errors.ErrorHandler.{defaultExceptionHandler, defaultRejectionHandler}
import ai.autodeploy.serving.errors.UnknownContentTypeException
import ai.autodeploy.serving.model.{JsonSupport, PredictRequest}
import ai.autodeploy.serving.protobuf.{fromPb, toPb, PredictRequest => PbPredictRequest}
import ai.autodeploy.serving.utils.Utils
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.{Complete, Rejected}
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.stream.scaladsl.FileIO
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

trait Endpoints extends JsonSupport {

  def akkaResponseTimeLoggingFunction(loggingAdapter: LoggingAdapter,
                                      requestTimestamp: Long,
                                      level: LogLevel = Logging.InfoLevel)(req: HttpRequest)(res: RouteResult): Unit = {
    val entry = res match {
      case Complete(resp) =>
        val responseTimestamp: Long = System.nanoTime
        val elapsedTime: Long = (responseTimestamp - requestTimestamp) / 1000000
        val loggingString = s"""Logged request - "${req.method.value} ${req.uri.path} ${req.protocol.value}" ${resp.status} $elapsedTime(ms)"""
        LogEntry(loggingString, level)
      case Rejected(reason) =>
        LogEntry(s"Rejected reason: ${reason.mkString(", ")}", level)
    }
    entry.logTo(loggingAdapter)
  }

  def printResponseTime(log: LoggingAdapter) = {
    val requestTimestamp = System.nanoTime
    akkaResponseTimeLoggingFunction(log, requestTimestamp)_
  }

  val logResponseTime = DebuggingDirectives.logRequestResult(LoggingMagnet(printResponseTime))


  def up(): Route = path("up") {
    complete(HttpResponse(
      status = StatusCodes.OK,
      entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "OK")))
  }

  def validate()(implicit ec: ExecutionContext): Route = path("v1" / "validate") {
    logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            extractRequestContext { ctx =>
              import ctx.materializer
              requestEntityPresent {
                withoutSizeLimit {
                  extractRequestEntity { entity =>
                    val dest = Utils.tempPath()
                    val uploadedFut = entity.dataBytes.runWith(FileIO.toPath(dest))
                    val validatedFut = uploadedFut.flatMap(_ => {
                      val modelType = Utils.inferModelType(dest, contentType = Some(entity.contentType))
                      ModelManager.validate(dest, modelType)
                    }).transform { case result =>
                      Utils.safeDelete(dest)
                      result
                    }

                    onSuccess(validatedFut) { result =>
                      complete(result)
                    }
                  }
                }
              }
            }
          }
      }
    }
  }

  def modelsV1()(implicit ec: ExecutionContext): Route = path("v1" / "models") {
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        get {
          onSuccess(ModelManager.getMetadataAll()) { result =>
            complete(result)
          }
        }
      }
    }
  }

  def modelV1()(implicit ec: ExecutionContext): Route = path("v1" / "models" / Segment) { modelName =>
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        handleRejections(defaultRejectionHandler) {
          put {
            extractRequestContext { ctx =>
              import ctx.materializer
              requestEntityPresent {
                withoutSizeLimit {
                  extractRequestEntity { entity =>
                    val dest = Utils.tempPath()
                    val uploadedFut = entity.dataBytes.runWith(FileIO.toPath(dest))
                    val deployedFut = uploadedFut.flatMap(_ => {
                      val modelType = Utils.inferModelType(dest, contentType = Some(entity.contentType))
                      ModelManager.deploy(dest, modelType, modelName)
                    }).transform { case result =>
                      Utils.safeDelete(dest)
                      result
                    }

                    onSuccess(deployedFut) { result =>
                      complete(StatusCodes.Created, result)
                    }
                  }
                }
              }
            }
          } ~
            post {
              predict(modelName)
            } ~
            get {
              onSuccess(ModelManager.getMetadata(modelName)) { result =>
                complete(result)
              }
            } ~
            delete {
              onSuccess(ModelManager.undeploy(modelName)) { _ =>
                complete(StatusCodes.NoContent)
              }
            }
        }
      }
    }
  }

  def modelVersionV1()(implicit ec: ExecutionContext): Route = path("v1" / "models" / Segment / "versions" / IntNumber) {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(ModelManager.getMetadata(modelName, Some(modelVersion))) { result =>
                complete(result)
              }
            } ~ post {
              predict(modelName, Some(modelVersion))
            }
          }
        }
      }
  }


  def predict(modelName: String, modelVersion: Option[Int] = None)(implicit ec: ExecutionContext): Route = {
    extractRequestContext { ctx =>
      import ctx.materializer
      requestEntityPresent {
        Option(ctx.request.entity.contentType) match {
          case Some(value) => value.mediaType.value match {
            case "application/json" => {
              entity(as[PredictRequest]) { payload =>
                val predictedFut = ModelManager.predict(payload, modelName, modelVersion)

                onSuccess(predictedFut) { result =>
                  complete(result)
                }
              }
            }
            case "application/vnd.google.protobuf" | "application/x-protobuf" | "application/octet-stream"
                                    => {
              extractDataBytes { data =>
                val bytesFut: Future[ByteString] = data.runFold(ByteString.empty) { case (acc, b) => acc ++ b }
                val predictedPbFut = bytesFut.flatMap { bytes =>
                  val pbRequest = PbPredictRequest.parseFrom(bytes.toArray)
                  val payload = fromPb(pbRequest)
                  ModelManager.predict(payload, modelName, modelVersion).map(x => {
                    toPb(x).copy(modelSpec = pbRequest.modelSpec).toByteArray
                  })
                }

                onSuccess(predictedPbFut) { result =>
                  complete(HttpResponse(
                    status = StatusCodes.OK,
                    entity = HttpEntity(result)))
                }
              }
            }
            case other              =>
              throw new UnknownContentTypeException(Utils.toOption(other))

          }
          case _           => throw UnknownContentTypeException()
        }
      }
    }
  }
}

