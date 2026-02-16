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

package com.autodeployai.serving

import com.autodeployai.serving.protobuf._
import com.autodeployai.serving.utils.DataUtils
import com.google.protobuf.ByteString
import inference.InferParameter.ParameterChoice
import inference.{InferTensorContents, ModelInferRequest, ModelInferResponse, ModelMetadataRequest, ModelMetadataResponse, ModelReadyRequest, ModelReadyResponse}
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException

import java.nio.file.Files

class PmmlGrpcV2Spec extends BaseGrpcSpec {

  // The model is from http://dmg.org/pmml/pmml_examples/KNIME_PMML_4.1_Examples/single_iris_dectree.xml

  "The GRPC service V2 of serving PMML" should {

    "return a prediction response for calling 'modelInfer'" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map.empty,
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "sepal_length", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(5.1)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "sepal_width", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(3.5)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_length", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(1.4)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_width", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(0.2)
            ))
          ),
        ),
        outputs = Seq.empty,
        rawInputContents = Seq.empty
      ))
      modelInferResponse shouldEqual ModelInferResponse(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map.empty,
        outputs = Seq(
          ModelInferResponse.InferOutputTensor(name = "predicted_class", datatype = "BYTES", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            bytesContents = Seq(ByteString.copyFromUtf8("Iris-setosa"))
          ))),
          ModelInferResponse.InferOutputTensor(name = "probability", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            fp64Contents = Seq(1.0)
          ))),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-setosa", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            fp64Contents = Seq(1.0)
          ))),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-versicolor", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            fp64Contents = Seq(0.0)
          ))),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-virginica", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            fp64Contents = Seq(0.0)
          ))),
          ModelInferResponse.InferOutputTensor(name = "node_id", datatype = "BYTES", shape = Seq(1), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            bytesContents = Seq(ByteString.copyFromUtf8("1"))
          ))),
        ),
        rawOutputContents = Seq.empty
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with rawInputContents" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true))),
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "sepal_length", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "sepal_width", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_length", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_width", datatype = "FP64", shape = Seq(2)
          ),
        ),
        outputs = Seq.empty,
        rawInputContents = Seq(
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(5.1, 7))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(3.5, 3.2))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(1.4, 4.7))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(0.2, 1.4)))
        )
      ))

      modelInferResponse shouldEqual ModelInferResponse(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true))),
        outputs = Vector(
          ModelInferResponse.InferOutputTensor(name = "predicted_class", datatype = "BYTES", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "probability", datatype = "FP64", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-setosa", datatype = "FP64", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-versicolor", datatype = "FP64", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "probability_Iris-virginica", datatype = "FP64", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "node_id", datatype = "BYTES", shape = Vector(2))
        ),
        rawOutputContents = Vector(
          DataUtils.writeBinaryString(Array("Iris-setosa", "Iris-versicolor")),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(1.0, 0.9074074074074074))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(1.0, 0.0))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(0.0, 0.9074074074074074))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(0.0, 0.09259259259259259))),
          DataUtils.writeBinaryString(Array("1", "3")),
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with specified outputs" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map.empty,
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "sepal_length", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(5.1, 7)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "sepal_width", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(3.5, 3.2)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_length", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(1.4, 4.7)
            ))
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_width", datatype = "FP64", shape = Seq(1), parameters = Map.empty, contents = Some(InferTensorContents(
              fp64Contents = Seq(0.2, 1.4)
            ))
          ),
        ),
        outputs = Seq(
          ModelInferRequest.InferRequestedOutputTensor(name = "predicted_class"),
          ModelInferRequest.InferRequestedOutputTensor(name = "probability")
        ),
        rawInputContents = Seq.empty
      ))
      modelInferResponse shouldEqual ModelInferResponse(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map.empty,
        outputs = Seq(
          ModelInferResponse.InferOutputTensor(name = "predicted_class", datatype = "BYTES", shape = Seq(2), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            bytesContents = Seq(ByteString.copyFromUtf8("Iris-setosa"), ByteString.copyFromUtf8("Iris-versicolor"))
          ))),
          ModelInferResponse.InferOutputTensor(name = "probability", datatype = "FP64", shape = Seq(2), parameters = Map.empty, contents = Some(inference.InferTensorContents(
            fp64Contents = Seq(1.0, 0.9074074074074074)
          )))
        ),
        rawOutputContents = Seq.empty
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with rawInputContents and specified outputs" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true))),
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "sepal_length", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "sepal_width", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_length", datatype = "FP64", shape = Seq(2)
          ),
          ModelInferRequest.InferInputTensor(
            name = "petal_width", datatype = "FP64", shape = Seq(2)
          ),
        ),
        outputs = Seq(
          ModelInferRequest.InferRequestedOutputTensor(name = "predicted_class"),
          ModelInferRequest.InferRequestedOutputTensor(name = "probability")
        ),
        rawInputContents = Seq(
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(5.1, 7))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(3.5, 3.2))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(1.4, 4.7))),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(0.2, 1.4)))
        )
      ))

      modelInferResponse shouldEqual ModelInferResponse(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true))),
        outputs = Vector(
          ModelInferResponse.InferOutputTensor(name = "predicted_class", datatype = "BYTES", shape = Vector(2)),
          ModelInferResponse.InferOutputTensor(name = "probability", datatype = "FP64", shape = Vector(2))
        ),
        rawOutputContents = Vector(
          DataUtils.writeBinaryString(Array("Iris-setosa", "Iris-versicolor")),
          ByteString.copyFrom(DataUtils.convertToByteArray(Array(1.0, 0.9074074074074074))),
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'modelMetadata' with the specified model and version" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelMetadataResponse = blockingStubV2().modelMetadata(ModelMetadataRequest(name = name, version = version))
      modelMetadataResponse shouldEqual ModelMetadataResponse(
        name = name,
        versions = Seq(version),
        platform = "pmml_pmmlv4",
        inputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "sepal_length", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "sepal_width", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "petal_length", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "petal_width", datatype = "FP64", shape = Seq(-1)),
        ),
        outputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "predicted_class", datatype = "BYTES", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-setosa", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-versicolor", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-virginica", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "node_id", datatype = "BYTES", shape = Seq(-1)),
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'modelMetadata' with the specified model" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")

      val modelMetadataResponse = blockingStubV2().modelMetadata(ModelMetadataRequest(name = name))
      modelMetadataResponse shouldEqual ModelMetadataResponse(
        name = name,
        versions = Seq(version),
        platform = "pmml_pmmlv4",
        inputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "sepal_length", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "sepal_width", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "petal_length", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "petal_width", datatype = "FP64", shape = Seq(-1)),
        ),
        outputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "predicted_class", datatype = "BYTES", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-setosa", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-versicolor", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "probability_Iris-virginica", datatype = "FP64", shape = Seq(-1)),
          ModelMetadataResponse.TensorMetadata(name = "node_id", datatype = "BYTES", shape = Seq(-1)),
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return true with a deployed model" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      val version = deployResponse.modelSpec.map(x => x.version).getOrElse("")
      val modelReady = blockingStubV2().modelReady(ModelReadyRequest(name = name, version=version))

      modelReady shouldEqual ModelReadyResponse(ready=true)
    }

    "return a NOT_FOUND error with a model that does not exist" in {
      try {
        blockingStubV2().modelReady(ModelReadyRequest(name = "not-exist-model"))
      } catch {
        case ex: StatusRuntimeException => ex.getStatus.getCode shouldEqual Code.NOT_FOUND
      }
    }

    "return a NOT_FOUND error with a specified version of model that does not exist" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))

      try {
        blockingStubV2().modelReady(ModelReadyRequest(name = name, version = "2"))
      } catch {
        case ex: StatusRuntimeException => ex.getStatus.getCode shouldEqual Code.NOT_FOUND
      }

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }


  }

}
