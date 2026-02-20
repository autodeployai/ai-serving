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
import com.autodeployai.serving.utils.Utils
import com.google.protobuf.ByteString
import inference.InferParameter.ParameterChoice
import inference.{InferTensorContents, ModelInferRequest, ModelInferResponse, ModelMetadataRequest, ModelMetadataResponse, ModelReadyRequest}
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class OnnxGrpcV2BatchSpec extends BaseGrpcSpec {

  // The model is MobileNet v2 from https://github.com/onnx/models/tree/main/validated/vision/classification/mobilenet
  private val inputTensor = TensorProto.parseFrom(Files.readAllBytes(getResource("mobilenet_v2_input_0.pb")))
  private val inputBuffer = ByteBuffer.wrap(inputTensor.rawData.toByteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  private val inputArray = new Array[Float](inputBuffer.remaining())
  inputBuffer.get(inputArray)

  private val outputTensor = TensorProto.parseFrom(Files.readAllBytes(getResource("mobilenet_v2_output_0.pb")))
  private val outputBuffer = ByteBuffer.wrap(outputTensor.rawData.toByteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  private val outputArray = new Array[Float](outputBuffer.remaining())
  outputBuffer.get(outputArray)

  private val input = ModelInferRequest(
    inputs = Seq(
      ModelInferRequest.InferInputTensor(
        name = "input",
        datatype = "FP32",
        shape = Seq(1, 3, 224, 224),
        contents = Some(InferTensorContents(fp32Contents = ArraySeq.unsafeWrapArray(inputArray)))
      )
    )
  )

  private val rawInput = ModelInferRequest(
    inputs = Seq(
      ModelInferRequest.InferInputTensor(
        name = "input",
        datatype = "FP32",
        shape = Seq(1, 3, 224, 224)
      )
    ),
    rawInputContents = Seq(inputTensor.rawData)
  )

  private val expected = ModelInferResponse(
    outputs = Seq(
      ModelInferResponse.InferOutputTensor(
        name = "output",
        shape = Seq(1, 1000),
        datatype = "FP32",
        contents = Some(InferTensorContents(fp32Contents = ArraySeq.unsafeWrapArray(outputArray)))
      )
    )
  )

  private val rawExpected = ModelInferResponse(
    outputs = Seq(
      ModelInferResponse.InferOutputTensor(
        name = "output",
        shape = Seq(1, 1000),
        datatype = "FP32"
      )
    ),
    rawOutputContents = Seq(outputTensor.rawData)
  )

  "The GRPC service V2 of serving ONNX with batch/timeout config" should {

    "return prediction response for calling 'modelInfer' with batch enabled" in {
      val name = "an-onnx-mobilenet-model"
      val deployResponse = blockingStub().deploy(
        DeployRequest(name,
          ByteString.copyFrom(Files.readAllBytes(getResource("mobilenet_v2.onnx"))),
          "ONNX",
          Some(DeployConfig(maxBatchSize=8, maxBatchDelayMs=1000L)
          )
        )
      )
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[Future[inference.ModelInferResponse]]()
      val nLoop = 10
      for (i <- 0 until nLoop) {
        val modelInferResponse = stubV2().modelInfer(
          input.copy(modelName = name, modelVersion = version, id = s"$i")
        )
        responses += modelInferResponse
      }

      for (i <- (0 until nLoop).reverse) {
        val actual =  Await.result(responses(i), Duration.Inf)
        actual.id shouldEqual s"$i"
        actual.modelName shouldEqual name
        actual.modelVersion shouldEqual version
        actual.outputs.head.contents.get.fp32Contents shouldEqual expected.outputs.head.contents.get.fp32Contents
      }

      log.info(s"The elapsed time of $nLoop requests with batch enabled is: ${System.currentTimeMillis() - start}")
      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'modelInfer' with rawInputContents with batch enabled" in {
      val name = "an-onnx-mobilenet-model"
      val deployResponse = blockingStub().deploy(
        DeployRequest(name,
          ByteString.copyFrom(Files.readAllBytes(getResource("mobilenet_v2.onnx"))),
          "ONNX",
          Some(DeployConfig(maxBatchSize=8, maxBatchDelayMs=1000L)
          )
        )
      )
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[Future[inference.ModelInferResponse]]()
      val nLoop = 10
      for (i <- 0 until nLoop) {
        val modelInferResponse = stubV2().modelInfer(
          rawInput.copy(
            modelName = name,
            modelVersion = version,
            id = s"$i",
            parameters = Map("raw_output" -> inference.InferParameter(parameterChoice=ParameterChoice.BoolParam(true)))
          )
        )
        responses += modelInferResponse
      }

      for (i <- (0 until nLoop).reverse) {
        val actual =  Await.result(responses(i), Duration.Inf)
        actual.id shouldEqual s"$i"
        actual.modelName shouldEqual name
        actual.modelVersion shouldEqual version

        actual.rawOutputContents.head shouldEqual rawExpected.rawOutputContents.head
      }

      log.info(s"The elapsed time of $nLoop requests with batch enabled is: ${System.currentTimeMillis() - start}")
      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return prediction response for calling 'modelInfer' with batch enabled and small delay timeout" in {
      val name = "an-onnx-mobilenet-model"
      val deployResponse = blockingStub().deploy(
        DeployRequest(name,
          ByteString.copyFrom(Files.readAllBytes(getResource("mobilenet_v2.onnx"))),
          "ONNX",
          Some(DeployConfig(maxBatchSize=8, maxBatchDelayMs=100L)
          )
        )
      )
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[Future[inference.ModelInferResponse]]()
      val (nOuterLoop , nLoop) = (2, 5)
      for (i <- 0 until nOuterLoop) {
        for (j <- 0 until  nLoop) {
          val modelInferResponse = stubV2().modelInfer(
            input.copy(modelName = name, modelVersion = version, id = s"$i-$j")
          )
          responses += modelInferResponse
        }
        Thread.sleep(1000L)
      }

      var k = 0
      for (i <- 0 until nOuterLoop) {
        for (j <- 0 until nLoop) {
          val actual =  Await.result(responses(k), Duration.Inf)
          actual.id shouldEqual s"$i-$j"
          actual.modelName shouldEqual name
          actual.modelVersion shouldEqual version
          actual.outputs.head.contents.get.fp32Contents shouldEqual expected.outputs.head.contents.get.fp32Contents
          k += 1
        }
      }

      log.info(s"The elapsed time of $nLoop requests with batch enabled is: ${System.currentTimeMillis() - start}")
      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response with large timeout enabled" in {
      val name = "an-onnx-mobilenet-model-timeout"
      val deployResponse = blockingStub().deploy(
        DeployRequest(name,
          ByteString.copyFrom(Files.readAllBytes(getResource("mobilenet_v2.onnx"))),
          "ONNX",
          Some(DeployConfig(requestTimeoutMs=100L)
          )
        )
      )

      val actual = blockingStubV2().modelInfer(input.copy(modelName = name))
      actual.outputs.head.contents.get.fp32Contents shouldEqual expected.outputs.head.contents.get.fp32Contents

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return an error response with small timeout enabled" in {
      val name = "an-onnx-mobilenet-model-timeout"
      val deployResponse = blockingStub().deploy(
        DeployRequest(name,
          ByteString.copyFrom(Files.readAllBytes(getResource("mobilenet_v2.onnx"))),
          "ONNX",
          Some(DeployConfig(requestTimeoutMs=1L)
          )
        )
      )

      try {
        blockingStubV2().modelInfer(input.copy(modelName = name,
          inputs=Seq(
            ModelInferRequest.InferInputTensor(
              name = "input",
              datatype = "FP32",
              shape = Seq(8, 3, 224, 224),
              contents = Some(InferTensorContents(fp32Contents = ArraySeq.unsafeWrapArray(Utils.flatten(Array.fill(8)(inputArray)).asInstanceOf[Array[Float]])))
            )
          )
        ))
      } catch {
        case ex: StatusRuntimeException => ex.getStatus.getCode shouldEqual Code.DEADLINE_EXCEEDED
      }
      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'modelMetadata' with the specified model" in {
      val name = "an-onnx-mobilenet-model"
      val input = getResource("mobilenet_v2.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))
      val version = deployResponse.modelSpec.map(_.version).getOrElse("")

      val modelMetadataResponse = blockingStubV2().modelMetadata(ModelMetadataRequest(name = name))
      modelMetadataResponse shouldEqual ModelMetadataResponse(
        name = name,
        versions = Seq(version),
        platform = "onnx_onnxv1",
        inputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "input", datatype = "FP32", shape = Seq(-1, 3, 224, 224))
        ),
        outputs = Seq(
          ModelMetadataResponse.TensorMetadata(name = "output", datatype = "FP32", shape = Seq(-1, 1000)
          )
        )
      )
      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }
  }
}
