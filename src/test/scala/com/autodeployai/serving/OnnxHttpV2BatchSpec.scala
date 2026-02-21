/*
 * Copyright (c) 2026 AutoDeployAI
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

import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.stream.scaladsl.Source
import com.autodeployai.serving.protobuf.TensorProto
import com.autodeployai.serving.model.{DeployConfig, InferenceRequest, InferenceResponse, JsonSupport, MetadataTensor, ModelMetadataV2, RequestInput, ResponseOutput}

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import scala.collection.mutable
import spray.json._

class OnnxHttpV2BatchSpec extends BaseHttpSpec with JsonSupport {

  // The model is MobileNet v2 from https://github.com/onnx/models/tree/main/validated/vision/classification/mobilenet
  private val inputTensor = TensorProto.parseFrom(Files.readAllBytes(getResource("mobilenet_v2_input_0.pb")))
  private val inputBuffer = ByteBuffer.wrap(inputTensor.rawData.toByteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  private val inputArray = new Array[Float](inputBuffer.remaining())
  inputBuffer.get(inputArray)

  private val outputTensor = TensorProto.parseFrom(Files.readAllBytes(getResource("mobilenet_v2_output_0.pb")))
  private val outputBuffer = ByteBuffer.wrap(outputTensor.rawData.toByteArray).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
  private val outputArray = new Array[Float](outputBuffer.remaining())
  outputBuffer.get(outputArray)

  private val input = InferenceRequest(
    inputs=Seq(
      RequestInput(
        name = "input",
        shape = Seq(1, 3, 224, 224),
        datatype = "FP32",
        data = inputArray
      )
    )
  )
  private val output = InferenceResponse(
    outputs=Seq(
      ResponseOutput(
        name = "output",
        shape = Seq(1, 1000),
        datatype = "FP32",
        data = outputArray
      )
    )
  )

  "The HTTP service V2 of serving ONNX with batch/timeout config" should {

    "return prediction responses with batch enabled" in {
      val name = "an-onnx-mobilenet-model-batch-enabled"
      val deployConfig = DeployConfig(
        maxBatchSize=Some(8),
        maxBatchDelayMs=Some(1000L),
        warmupCount=Some(100),
        warmupDataType=Some("random")
      )
      val deployResponse = deployModelWithConfig(name, "mobilenet_v2.onnx", `application/octet-stream`, deployConfig)

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[RouteTestResult]()
      val nLoop = 10
      for (i <- 0 until nLoop) {
        val response = Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", input.copy(id=Some(s"$i"))) ~> route ~> runRoute
        responses += response
      }

      for (i <- (0 until nLoop).reverse) {
        check {
          status shouldEqual StatusCodes.OK
          val actual = responseAs[InferenceResponse]
          val expected = output.copy(
            model_name=name,
            model_version=Some(deployResponse.version),
            id=Some(s"$i"))
          actual.id shouldEqual expected.id
          actual.dataToSeq.outputs.head.data shouldEqual expected.dataToSeq.outputs.head.data
        } (responses(i))
      }

      log.info(s"The elapsed time of $nLoop requests with batch enabled is: ${System.currentTimeMillis() - start}")
      undeployModel(name)
    }

    "return prediction responses with batch disabled" in {
      val name = "an-onnx-mobilenet-model-batch-disabled"
      val deployConfig = DeployConfig(
        warmupCount=Some(100),
        warmupDataType=Some("random")
      )
      val deployResponse = deployModelWithConfig(name, "mobilenet_v2.onnx", `application/octet-stream`, deployConfig)

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[RouteTestResult]()
      val nLoop = 10
      for (i <- 0 until nLoop) {
        val response = Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", input.copy(id=Some(s"$i"))) ~> route ~> runRoute
        responses += response
      }

      for (i <- (0 until nLoop).reverse) {
        check {
          status shouldEqual StatusCodes.OK
          val actual = responseAs[InferenceResponse]
          val expected = output.copy(
            model_name=name,
            model_version=Some(deployResponse.version),
            id=Some(s"$i"))
          actual.id shouldEqual expected.id
          actual.dataToSeq.outputs.head.data shouldEqual expected.dataToSeq.outputs.head.data
        } (responses(i))
      }

      log.info(s"The elapsed time of $nLoop requests with batch disabled is: ${System.currentTimeMillis() - start}")
      undeployModel(name)
    }

    "return prediction responses with batch with small delay timeout and warmup enabled" in {
      val name = "an-onnx-mobilenet-model-batch-small-delayed-enabled"
      val deployConfig = DeployConfig(
        maxBatchSize=Some(8),
        maxBatchDelayMs=Some(100L),
        warmupCount=Some(100),
        warmupDataType=Some("zero")
      )
      val deployResponse = deployModelWithConfig(name, "mobilenet_v2.onnx", `application/octet-stream`, deployConfig)

      val start = System.currentTimeMillis()
      val responses = mutable.ArrayBuffer[RouteTestResult]()
      val (nOuterLoop , nLoop) = (2, 5)
      for (i <- 0 until nOuterLoop) {
        for (j <- 0 until  nLoop) {
          val response = Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", input.copy(id=Some(s"$i-$j"))) ~> route ~> runRoute
          responses += response
        }
        Thread.sleep(1000L)
      }

      var k = 0
      for (i <- 0 until nOuterLoop) {
        for (j <- 0 until  nLoop) {
          check {
            status shouldEqual StatusCodes.OK
            val actual = responseAs[InferenceResponse]
            val expected = output.copy(
              model_name=name,
              model_version=Some(deployResponse.version),
              id=Some(s"$i-$j"))
            actual.id shouldEqual expected.id
            actual.dataToSeq.outputs.head.data shouldEqual expected.dataToSeq.outputs.head.data
          } (responses(k))
          k += 1
        }
      }

      log.info(s"The elapsed time of $nLoop requests with batch enabled is: ${System.currentTimeMillis() - start}")
      undeployModel(name)
    }

    "return a prediction response with large timeout enabled" in {
      val name = "an-onnx-mobilenet-model-timeout"
      val deployConfig = DeployConfig(requestTimeoutMs=Some(100))
      val deployResponse = deployModelWithConfig(name, "mobilenet_v2.onnx", `application/octet-stream`, deployConfig)

      Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", input.copy(id=Some("success"))) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        val actual = responseAs[String]
        val expected = output.copy(
          model_name=name,
          model_version=Some(deployResponse.version),
          id=Some("success"))
        actual shouldEqual expected.toJson.toString
      }
      undeployModel(name)
    }

    "return a 504 error response with small timeout enabled" in {
      val name = "an-onnx-mobilenet-model-timeout"
      val deployConfig = DeployConfig(requestTimeoutMs=Some(1))
      val deployResponse = deployModelWithConfig(name, "mobilenet_v2.onnx", `application/octet-stream`, deployConfig)

      val nLoop = 10
      val nBatch = 16 // Set to a bigger number if it's getting
      for (_ <- 0 until nLoop) {
        val batchInput = InferenceRequest(
          inputs=Seq(
            RequestInput(
              name = "input",
              shape = Seq(nBatch, 3, 224, 224),
              datatype = "FP32",
              data = Array.fill(nBatch)(inputArray)
            )
          )
        )
        Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", batchInput.copy(id=Some("timeout"))) ~> route ~> check {
          if (status == StatusCodes.OK) {
            log.warn("Your computer is more powerful, please set nBatch to a bigger number to reproduce timeout")
          } else {
            status shouldEqual StatusCodes.GatewayTimeout
            val actual = responseAs[com.autodeployai.serving.errors.Error]
            actual.error.contains(s"A request to $name:${deployResponse.version} exceeded timeout") shouldEqual true
          }
        }
      }

      // Wait for a while to make sure the pending requests are done
      Thread.sleep(10000)
      undeployModel(name)
    }

    "return a model metadata response with specified version for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}" in {
      val name = "an-onnx-mobilenet-model"
      val deployResponse = deployModel(name, "mobilenet_v2.onnx", `application/octet-stream`)

      val expected = ModelMetadataV2(
        name = name,
        versions = Seq("1"),
        platform = "onnx_onnxv1",
        inputs = Seq(
          MetadataTensor(name = "input", datatype = "FP32", shape = Seq(-1, 3, 224, 224))
        ),
        outputs = Seq(
          MetadataTensor(name = "output", datatype = "FP32", shape = Seq(-1, 1000)),
        )
      )

      Get(s"/v2/models/$name/versions/${deployResponse.version}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        log.info(responseAs[String])
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      Get(s"/v2/models/$name") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        log.info(responseAs[String])
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      undeployModel(name)
    }

    "return a 400 error when there is no model specified with FormData" in {
      val name = "no-model-specified"

      val deployConfig = DeployConfig(maxBatchSize=Some(8), maxBatchDelayMs=Some(1000L))
      val configPart = Multipart.FormData.BodyPart.Strict(
        "config",
        HttpEntity(ContentTypes.`application/json`, deployConfig.toJson.toString())
      )

      val formData = Multipart.FormData(Source(List(configPart)))
      Put(s"/v1/models/$name", formData) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }
}
