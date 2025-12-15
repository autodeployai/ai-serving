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
package com.autodeployai.serving.protobuf

import inference.{GRPCInferenceServiceGrpc, ModelInferRequest, ModelInferResponse, ModelMetadataRequest, ModelMetadataResponse, ModelReadyRequest, ModelReadyResponse, ServerLiveRequest, ServerLiveResponse, ServerMetadataRequest, ServerMetadataResponse, ServerReadyRequest, ServerReadyResponse}
import com.autodeployai.serving.AIServer.executionContext
import com.autodeployai.serving.deploy.ModelManager
import com.autodeployai.serving.errors.ErrorHandler.grpcHandler
import com.autodeployai.serving.utils.Utils

import scala.concurrent.Future
import scala.util.{Failure, Success}

class DeploymentServiceImplV2 extends GRPCInferenceServiceGrpc.GRPCInferenceService {

  /** The ServerLive API indicates if the inference server is able to receive
   * and respond to metadata and inference requests.
   */
  override def serverLive(request: ServerLiveRequest): Future[ServerLiveResponse] = Future {
    ServerLiveResponse(live=true)
  }

  /** The ServerReady API indicates if the server is ready for inferencing.
   */
  override def serverReady(request: ServerReadyRequest): Future[ServerReadyResponse] = Future {
    ServerReadyResponse(ready=true)
  }

  /** The ModelReady API indicates if a specific model is ready for inferencing.
   */
  override def modelReady(request: ModelReadyRequest): Future[ModelReadyResponse] = {
    ModelManager.isModelReady(request.name, Utils.toOption(request.version)).transform {
      case Success(value) =>
        Success(ModelReadyResponse(ready = value))
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  /** The ServerMetadata API provides information about the server. Errors are
   * indicated by the google.rpc.Status returned for the request. The OK code
   * indicates success and other codes indicate failure.
   */
  override def serverMetadata(request: ServerMetadataRequest): Future[ServerMetadataResponse] = Future {
    toPb(Utils.getServerMetadata)
  }

  /** The per-model metadata API provides information about a model. Errors are
   * indicated by the google.rpc.Status returned for the request. The OK code
   * indicates success and other codes indicate failure.
   */
  override def modelMetadata(request: ModelMetadataRequest): Future[ModelMetadataResponse] = {
    ModelManager.getMetadataV2(request.name, Utils.toOption(request.version)).
      map(x => toPb(x)).
      transform {
      case Success(result) =>
        Success(result)
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  /** The ModelInfer API performs inference using the specified model. Errors are
   * indicated by the google.rpc.Status returned for the request. The OK code
   * indicates success and other codes indicate failure.
   */
  override def modelInfer(request: ModelInferRequest): Future[ModelInferResponse] = {
    ModelManager.predict(fromPb(request), request.modelName, Utils.toOption(request.modelVersion), grpc = true).
      map(x => toPb(x)).
      transform {
      case Success(result) =>
        Success(result)
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }
}
