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

import com.autodeployai.serving.model.{DataType, InferenceRequest, InferenceResponse}
import com.autodeployai.serving.grpc._
import com.autodeployai.serving.protobuf._
import com.autodeployai.serving.utils.DataUtils
import com.google.protobuf.ByteString
import inference.InferParameter.ParameterChoice
import inference.{InferTensorContents, ModelInferRequest, ModelMetadataRequest, ModelMetadataResponse}

import java.nio.ByteOrder
import java.nio.file.Files
import scala.collection.immutable.ArraySeq

class OnnxGrpcV2Spec extends BaseGrpcSpec {

  // The model is ONNX 1.3 from https://github.com/onnx/models/tree/master/vision/classification/mnist
  // NOTE: Test cases are disabled by default because users need to configure libraries of Onnx Runtime before running them
  // You can remove the Ignore annotation above the test Class to free all them all

  "The GRPC service V2 of serving ONNX" should {

    "return a prediction response for calling 'modelInfer'" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))

      val input0 = loadJson[InferenceRequest](getResource("mnist_request_v2_0.json"))
      val expected = loadJson[InferenceResponse](getResource("mnist_response_v2_0.json"))

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = deployResponse.modelSpec.map(_.version).getOrElse(""),
        id = "42",
        parameters = Map.empty,
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "Input3", datatype = "FP32", shape = Seq(1, 1, 28, 28), parameters = Map.empty, contents = Some(InferTensorContents(
              fp32Contents = ArraySeq.unsafeWrapArray(input0.inputs.head.data.asInstanceOf[Array[Float]])
            ))
          )
        ),
        outputs = Seq.empty,
        rawInputContents = Seq.empty
      ))

      val expectedResponse = toPb(expected)
      modelInferResponse.outputs.head.contents.get.fp32Contents shouldEqual expectedResponse.outputs.head.contents.get.fp32Contents

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with rawInputContents" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map.empty,
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "Input3", datatype = "FP32", shape = tensor0.dims, parameters = Map.empty, contents = None
          )
        ),
        outputs = Seq.empty,
        rawInputContents = Seq(tensor0.rawData)
      ))

      val expected = getFloatTensor("mnist_output_0.pb")
      modelInferResponse.outputs.head.contents.get.fp32Contents.zip(expected).foreach { x =>
        x._1 shouldEqual x._2
      }

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with rawInputContents and raw_output" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))

      val modelInferResponse = blockingStubV2().modelInfer(ModelInferRequest(
        modelName = name,
        modelVersion = version,
        id = "42",
        parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true))),
        inputs = Seq(
          ModelInferRequest.InferInputTensor(
            name = "Input3", datatype = "FP32", shape = tensor0.dims, parameters = Map.empty, contents = None
          )
        ),
        outputs = Seq.empty,
        rawInputContents = Seq(tensor0.rawData)
      ))

      val expected = getFloatTensor("mnist_output_0.pb")
      val actual = DataUtils.convertToArray(modelInferResponse.rawOutputContents.head.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN), DataType.FP32)
      actual.zip(expected).foreach { x =>
        x._2 shouldEqual x._1
      }

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'modelMetadata' with the specified model" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val modelMetadataResponse = blockingStubV2().modelMetadata(ModelMetadataRequest(name = name))
      modelMetadataResponse shouldEqual ModelMetadataResponse(
        name = name,
        versions = Seq(version),
        platform = "onnx_onnxv1",
        inputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "Input3", datatype = "FP32", shape = Seq(1, 1, 28, 28))
        ),
        outputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "Plus214_Output_0", datatype = "FP32", shape = Seq(1, 10)
          )
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

  }
}
