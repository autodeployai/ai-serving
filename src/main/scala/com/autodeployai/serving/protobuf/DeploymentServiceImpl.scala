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

package com.autodeployai.serving.protobuf

import com.autodeployai.serving.AIServer.executionContext
import com.autodeployai.serving.deploy.ModelManager
import com.autodeployai.serving.errors.ErrorHandler.grpcHandler
import com.autodeployai.serving.utils.{IOUtils, Utils}
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class DeploymentServiceImpl extends DeploymentServiceGrpc.DeploymentService {

  override def validate(request: ValidateRequest): Future[ModelInfo] = {
    val path = IOUtils.write(Utils.tempPath(), request.model)
    val modelType = Utils.inferModelType(path, Utils.toOption(request.`type`))

    ModelManager.validate(path, modelType).transform { result =>
        Utils.safeDelete(path)
        result match {
          case Success(value)     =>
            Success(value.toPb)
          case Failure(exception) =>
            Failure(grpcHandler(exception))
        }
    }
  }

  override def deploy(request: DeployRequest): Future[DeployResponse] = {
    val path = IOUtils.write(Utils.tempPath(), request.model)
    val modelType = Utils.inferModelType(path, Utils.toOption(request.`type`))
    ModelManager.deploy(path, modelType, request.name).transform { result =>
        Utils.safeDelete(path)
        result match {
          case Success(value)     =>
            Success(value.toPb)
          case Failure(exception) =>
            Failure(grpcHandler(exception))
        }
    }
  }

  override def undeploy(request: UndeployRequest): Future[UndeployResponse] = {
    val modelSpec = ensureModelSpec(request.modelSpec)
    ModelManager.undeploy(modelSpec.name).transform {
      case Success(_)         =>
        Success(UndeployResponse(request.modelSpec))
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  override def predict(request: PredictRequest): Future[PredictResponse] = {
    val modelSpec = ensureModelSpec(request.modelSpec)
    ModelManager.predict(fromPb(request), modelSpec.name, Utils.toOption(modelSpec.version), grpc = true).
      map(x => toPb(x).withModelSpec(modelSpec)).
      transform {
      case Success(result)    =>
        Success(result)
      case Failure(exception) =>
        Failure(grpcHandler(exception))
    }
  }

  override def getModelMetadata(request: GetModelMetadataRequest): Future[GetModelMetadataResponse] = request.modelSpec match {
    case Some(modelSpec) if Utils.nonEmpty(modelSpec.name) =>
      ModelManager.getMetadata(modelSpec.name).
        map(x => toPb(x)).
        transform {
        case Success(value)     =>
          Success(GetModelMetadataResponse(request.modelSpec, metadata = Seq(value)))
        case Failure(exception) =>
          Failure(grpcHandler(exception))
      }
    case _                                                 =>
      ModelManager.getMetadataAll().
        map(x => x.map(toPb)).
        transform {
        case Success(value)     =>
          Success(GetModelMetadataResponse(metadata = value))
        case Failure(exception) =>
          Failure(grpcHandler(exception))
      }
  }

  private def ensureModelSpec(modelSpec: Option[ModelSpec]): ModelSpec = modelSpec match {
    case Some(value) if Utils.nonEmpty(value.name) => value
    case _                                         =>
      throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Missing model spec or name in the input request"))
  }
}
