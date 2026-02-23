/*
 * Copyright (c) 2019-2026 AutoDeployAI
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

package com.autodeployai.serving.http

import com.autodeployai.serving.errors.ErrorHandler.{defaultExceptionHandler, defaultRejectionHandler}
import com.autodeployai.serving.grpc.{fromPb, toPb}
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpResponse, Multipart, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.errors.{BaseException, UnknownContentTypeException}
import com.autodeployai.serving.model.{DeployConfig, JsonSupport, PredictRequest}
import com.autodeployai.serving.protobuf
import com.autodeployai.serving.utils.Utils
import org.slf4j.Logger
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

trait Endpoints extends JsonSupport with HttpSupport {
  def log: Logger

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
                    val dest = Utils.tempFilePath()
                    val uploadedFut = entity.dataBytes.runWith(FileIO.toPath(dest))
                    val validatedFut = uploadedFut.flatMap(_ => {
                      val modelType = Utils.inferModelType(dest, contentType = Some(entity.contentType))
                      InferenceService.validate(dest, modelType)
                    }).transform { result =>
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
          onSuccess(InferenceService.getMetadataAll()) { result =>
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
                  // Check if request is multipart/form-data
                  entity(as[Multipart.FormData]) { formData =>
                    handleMultipartDeploy(modelName, formData)
                  } ~
                  extractRequestEntity { entity =>
                    val dest = Utils.tempFilePath()
                    val uploadedFut = entity.dataBytes.runWith(FileIO.toPath(dest))
                    val deployedFut = uploadedFut.flatMap(_ => {
                      val modelType = Utils.inferModelType(dest, contentType = Some(entity.contentType))
                      InferenceService.deploy(dest, modelType, modelName)
                    }).transform { result =>
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
              onSuccess(InferenceService.getMetadata(modelName)) { result =>
                complete(result)
              }
            } ~
            delete {
              onSuccess(InferenceService.undeploy(modelName)) { _ =>
                complete(StatusCodes.NoContent)
              }
            }
        }
      }
    }
  }

  def modelVersionV1()(implicit ec: ExecutionContext): Route = path("v1" / "models" / Segment / "versions" / Segment) {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(InferenceService.getMetadata(modelName, Some(modelVersion))) { result =>
                complete(result)
              }
            } ~ post {
              predict(modelName, Some(modelVersion))
            }
          }
        }
      }
  }

  private def predict(modelName: String, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): Route = {
    extractRequestContext { ctx =>
      import ctx.materializer
      requestEntityPresent {
        Option(ctx.request.entity.contentType) match {
          case Some(value) => value.mediaType.value match {
            case "application/json" =>
              entity(as[PredictRequest]) { payload =>
                val predictedFut = InferenceService.predict(payload, modelName, modelVersion)

                onSuccess(predictedFut) { result =>
                  complete(result)
                }
              }
            case "application/vnd.google.protobuf" | "application/x-protobuf" | "application/octet-stream"  =>
              extractDataBytes { data =>
                val bytesFut: Future[ByteString] = data.runFold(ByteString.newBuilder) { (builder, chunk) =>
                  builder ++= chunk
                }.map(_.result())
                val predictedPbFut = bytesFut.flatMap { bytes =>
                  val pbRequest = protobuf.PredictRequest.parseFrom(bytes.toArray)
                  val payload = fromPb(pbRequest)
                  InferenceService.predict(payload, modelName, modelVersion).map(x => {
                    toPb(x).copy(modelSpec = pbRequest.modelSpec).toByteArray
                  })
                }

                onSuccess(predictedPbFut) { result =>
                  complete(HttpResponse(
                    status = StatusCodes.OK,
                    entity = HttpEntity(result)))
                }
            }
            case other              =>
              throw UnknownContentTypeException(Utils.toOption(other))

          }
          case _           => throw UnknownContentTypeException()
        }
      }
    }
  }

  private def handleMultipartDeploy(modelName: String, formData: Multipart.FormData)(implicit ec: ExecutionContext): Route = {
    extractRequestContext { ctx =>
      import ctx.materializer
      import scala.collection.mutable

      val modelFileRef = mutable.Map[String, java.nio.file.Path]()
      val configRef = mutable.Map[String, DeployConfig]()
      val contentTypeRef = mutable.Map[String, ContentType]()

      val processParts: Future[Int] = formData.parts.mapAsync(1) { part =>
        part.name match {
          case name if name == "model" || name == "file" =>
            // Save model file to temporary location
            val dest = Utils.tempFilePath()
            part.entity.dataBytes.runWith(FileIO.toPath(dest)).map { _ =>
              log.debug(s"A temporary model file $dest created")
              modelFileRef.put("model", dest)
              contentTypeRef.put("model", part.entity.contentType)
              ()
            }
          case name if name == "config" || name == "configuration" =>
            // Parse JSON configuration
            part.entity.dataBytes.runFold(ByteString.newBuilder) { (builder, chunk) =>
              builder ++= chunk
            }.map(_.result()).map { bytes =>
              val jsonString = bytes.utf8String
              try {
                if (jsonString.nonEmpty && jsonString.trim.nonEmpty) {
                  val config = jsonString.parseJson.convertTo[DeployConfig]
                  configRef.put("config", config)
                  log.info(s"Parsed deployment configuration: $config")
                }
              } catch {
                case ex: Exception =>
                  log.warn(s"Failed to parse deployment configuration JSON: ${ex.getMessage}", ex)
              }
              ()
            }
          case _ =>
            // Ignore unknown fields
            part.entity.discardBytes()
            Future.successful(())
        }
      }.runFold(0)((count, _) => count + 1)

      val deployedFut = processParts.flatMap { _ =>
        modelFileRef.get("model") match {
          case Some(filePath) =>
            val deployConfig = configRef.get("config")
            val modelType = Utils.inferModelType(filePath, contentType = contentTypeRef.get("model"))
            InferenceService.deploy(filePath, modelType, modelName, deployConfig)
          case _ =>
            throw new BaseException("Missing required field 'model' or 'file' in multipart form data")
        }
      } transform { result =>
        modelFileRef.get("model").foreach(filePath => Utils.safeDelete(filePath))
        result
      }

      onSuccess(deployedFut) { result =>
        complete(StatusCodes.Created, result)
      }
    }
  }
}
