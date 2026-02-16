/*
 * Copyright (c) 2019-2025 AutoDeployAI
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

package com.autodeployai.serving.grpc

import com.autodeployai.serving.AIServer.executionContext
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.errors.ErrorHandler.grpcHandler
import com.autodeployai.serving.protobuf
import com.autodeployai.serving.utils.Utils
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class DeploymentServiceImpl extends protobuf.DeploymentServiceGrpc.DeploymentService {

  override def validate(request: protobuf.ValidateRequest): Future[protobuf.ModelInfo] = {
    val path = Utils.write(Utils.tempFilePath(), request.model)
    val modelType = Utils.inferModelType(path, Utils.toOption(request.`type`))

    InferenceService.validate(path, modelType).transform { result =>
        Utils.safeDelete(path)
        result match {
          case Success(value)     =>
            Success(value.toPb)
          case Failure(exception) =>
            Failure(grpcHandler(exception))
        }
    }
  }

  override def deploy(request: protobuf.DeployRequest): Future[protobuf.DeployResponse] = {
    val path = Utils.write(Utils.tempFilePath(), request.model)
    val modelType = Utils.inferModelType(path, Utils.toOption(request.`type`))
    InferenceService.deploy(path, modelType, request.name, request.config.map(fromPb)).transform { result =>
        Utils.safeDelete(path)
        result match {
          case Success(value)     =>
            Success(value.toPb)
          case Failure(exception) =>
            Failure(grpcHandler(exception))
        }
    }
  }

  override def undeploy(request: protobuf.UndeployRequest): Future[protobuf.UndeployResponse] = {
    val modelSpec = ensureModelSpec(request.modelSpec)
    InferenceService.undeploy(modelSpec.name).transform {
      case Success(_)         =>
        Success(protobuf.UndeployResponse(request.modelSpec))
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  override def predict(request: protobuf.PredictRequest): Future[protobuf.PredictResponse] = {
    val modelSpec = ensureModelSpec(request.modelSpec)
    InferenceService.predict(fromPb(request), modelSpec.name, Utils.toOption(modelSpec.version)).
      map(x => toPb(x).withModelSpec(modelSpec)).
      transform {
      case Success(result)    =>
        Success(result)
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  override def getModelMetadata(request: protobuf.GetModelMetadataRequest): Future[protobuf.GetModelMetadataResponse] = request.modelSpec match {
    case Some(modelSpec) if Utils.nonEmpty(modelSpec.name) =>
      InferenceService.getMetadata(modelSpec.name).
        map(x => toPb(x)).
        transform {
        case Success(value)     =>
          Success(protobuf.GetModelMetadataResponse(request.modelSpec, metadata = Seq(value)))
        case Failure(exception) =>
          Failure(grpcHandler(exception))
      }
    case _                                                 =>
      InferenceService.getMetadataAll().
        map(x => x.map(toPb)).
        transform {
        case Success(value)     =>
          Success(protobuf.GetModelMetadataResponse(metadata = value))
        case Failure(exception) =>
          Failure(grpcHandler(exception))
      }
  }

  private def ensureModelSpec(modelSpec: Option[protobuf.ModelSpec]): protobuf.ModelSpec = modelSpec match {
    case Some(value) if Utils.nonEmpty(value.name) => value
    case _                                         =>
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Missing model spec or name in the input request"))
  }
}
