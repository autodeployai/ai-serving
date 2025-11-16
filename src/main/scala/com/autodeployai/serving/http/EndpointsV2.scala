/*
 * Copyright (c) 2025 AutoDeployAI
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

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.autodeployai.serving.deploy.ModelManager
import com.autodeployai.serving.errors.ErrorHandler.{defaultExceptionHandler, defaultRejectionHandler}
import com.autodeployai.serving.model.{InferenceRequest, JsonSupport}
import com.autodeployai.serving.utils.Utils

import scala.concurrent.ExecutionContext

trait EndpointsV2 extends JsonSupport with HttpSupport {

  def healthLiveV2(): Route = path("v2" / "health" / "live") {
    get {
      complete(HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity.Empty))
    }
  }

  def healthReadyV2(): Route = path("v2" / "health" / "ready") {
    get {
      complete(HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity.Empty))
    }
  }

  def modelReadyV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "ready") { modelName =>
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(ModelManager.isModelReady(modelName)) { ready =>
                complete(HttpResponse(
                  status = if (ready) StatusCodes.OK else StatusCodes.BadRequest,
                  entity = HttpEntity.Empty))
              }
            }
        }
      }
    }
  }

  def modelVersionReadyV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment / "ready") {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(ModelManager.isModelReady(modelName, Some(modelVersion))) { ready =>
                complete(HttpResponse(
                  status = if (ready) StatusCodes.OK else StatusCodes.BadRequest,
                  entity = HttpEntity.Empty))
              }
            }
          }
        }
      }
  }

  def serverV2(): Route = path("v2") {
    get {
      complete(Utils.getServerMetadata)
    }
  }

  def modelMetadataV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment) { modelName =>
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        handleRejections(defaultRejectionHandler) {
          get {
            onSuccess(ModelManager.getMetadataV2(modelName)) { result =>
              complete(result)
            }
          }
        }
      }
    }
  }

  def modelVersionMetadataV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment) {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(ModelManager.getMetadataV2(modelName, Some(modelVersion))) { result =>
                complete(result)
              }
            }
          }
        }
      }
  }

  def modelInferV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "infer") { modelName =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            post {
              entity(as[InferenceRequest]) { request =>
                onSuccess(ModelManager.predict(request, modelName, None, grpc = false)) { result =>
                  complete(result)
                }
              }
            }
          }
        }
      }
  }

  def modelVersionInferV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment / "infer") {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            post {
              entity(as[InferenceRequest]) { request =>
                onSuccess(ModelManager.predict(request, modelName, Some(modelVersion), grpc = false)) { result =>
                  complete(result)
                }
              }
            }
          }
        }
      }
  }
}