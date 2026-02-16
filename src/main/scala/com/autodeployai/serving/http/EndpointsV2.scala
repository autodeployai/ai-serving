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
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.errors.ErrorHandler.{defaultExceptionHandler, defaultRejectionHandler}
import com.autodeployai.serving.model.{InferenceRequest, JsonSupport}
import com.autodeployai.serving.utils.Utils

import scala.concurrent.ExecutionContext

/**
 * https://github.com/kserve/kserve/blob/master/docs/predict-api/v2/required_api.md
 */
trait EndpointsV2 extends JsonSupport with HttpSupport {

  /**
   * The “server live” API indicates if the inference server is able to receive and respond to metadata and inference requests.
   * The “server live” API can be used directly to implement the Kubernetes livenessProbe.
   * @return
   */
  def healthLiveV2(): Route = path("v2" / "health" / "live") {
    get {
      complete(HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, """{"live":true}""")))
    }
  }

  /**
   * The “server ready” health API indicates if all the models are ready for inferencing.
   * The “server ready” health API can be used directly to implement the Kubernetes readinessProbe.
   * @return
   */
  def healthReadyV2(): Route = path("v2" / "health" / "ready") {
    get {
      complete(HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, Utils.readyJson(true))))
    }
  }

  /**
   * The “model ready” health API indicates if a specific model is ready for inferencing.
   * The model name and (optionally) version must be available in the URL. If a version is
   * not provided the server may choose a version based on its own policies.
   * @param ec
   * @return
   */
  def modelReadyV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "ready") { modelName =>
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(InferenceService.isModelReady(modelName)) { ready =>
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(ContentTypes.`application/json`, Utils.readyJson(ready))))
              }
            }
        }
      }
    }
  }

  /**
   * The “model ready” health API indicates if a specific model is ready for inferencing.
   * The model name and (optionally) version must be available in the URL. If a version is
   * not provided the server may choose a version based on its own policies.
   * @param ec
   * @return
   */
  def modelVersionReadyV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment / "ready") {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(InferenceService.isModelReady(modelName, Some(modelVersion))) { ready =>
                complete(HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(ContentTypes.`application/json`, Utils.readyJson(ready))))
              }
            }
          }
        }
      }
  }

  /**
   * The server metadata endpoint provides information about the server.
   * A server metadata request is made with an HTTP GET to a server metadata endpoint.
   * In the corresponding response the HTTP body contains the Server Metadata Response JSON Object
   * or the Server Metadata Response JSON Error Object.
   * @return
   */
  def serverV2(): Route = path("v2") {
    get {
      complete(Utils.getServerMetadata)
    }
  }

  /**
   * The per-model metadata endpoint provides information about a model.
   * A model metadata request is made with an HTTP GET to a model metadata endpoint.
   * In the corresponding response the HTTP body contains the Model Metadata Response
   * JSON Object or the Model Metadata Response JSON Error Object. The model name and (optionally)
   * version must be available in the URL. If a version is not provided the server may choose
   * a version based on its own policies or return an error.
   * @param ec
   * @return
   */
  def modelMetadataV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment) { modelName =>
    logResponseTime {
      handleExceptions(defaultExceptionHandler) {
        handleRejections(defaultRejectionHandler) {
          get {
            onSuccess(InferenceService.getMetadataV2(modelName)) { result =>
              complete(result)
            }
          }
        }
      }
    }
  }

  /**
   * The per-model metadata endpoint provides information about a model.
   * A model metadata request is made with an HTTP GET to a model metadata endpoint.
   * In the corresponding response the HTTP body contains the Model Metadata Response
   * JSON Object or the Model Metadata Response JSON Error Object. The model name and (optionally)
   * version must be available in the URL. If a version is not provided the server may choose
   * a version based on its own policies or return an error.
   * @param ec
   * @return
   */
  def modelVersionMetadataV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment) {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            get {
              onSuccess(InferenceService.getMetadataV2(modelName, Some(modelVersion))) { result =>
                complete(result)
              }
            }
          }
        }
      }
  }

  /**
   * An inference request is made with an HTTP POST to an inference endpoint.
   * In the request the HTTP body contains the Inference Request JSON Object.
   * In the corresponding response the HTTP body contains the Inference Response
   * JSON Object or Inference Response JSON Error Object. See Inference Request
   * Examples for some example HTTP/REST requests and responses.
   * @param ec
   * @return
   */
  def modelInferV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "infer") { modelName =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            post {
              entity(as[InferenceRequest]) { request =>
                onSuccess(InferenceService.predict(request, modelName, None)) { result =>
                  complete(result)
                }
              }
            }
          }
        }
      }
  }

  /**
   * An inference request is made with an HTTP POST to an inference endpoint.
   * In the request the HTTP body contains the Inference Request JSON Object.
   * In the corresponding response the HTTP body contains the Inference Response
   * JSON Object or Inference Response JSON Error Object. See Inference Request
   * Examples for some example HTTP/REST requests and responses.
   * @param ec
   * @return
   */
  def modelVersionInferV2()(implicit ec: ExecutionContext): Route = path("v2" / "models" / Segment / "versions" / Segment / "infer") {
    (modelName, modelVersion) =>
      logResponseTime {
        handleExceptions(defaultExceptionHandler) {
          handleRejections(defaultRejectionHandler) {
            post {
              entity(as[InferenceRequest]) { request =>
                onSuccess(InferenceService.predict(request, modelName, Some(modelVersion))) { result =>
                  complete(result)
                }
              }
            }
          }
        }
      }
  }
}