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

import akka.http.scaladsl.model.ContentTypes.{`application/json`, `application/octet-stream`}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import com.autodeployai.serving.model.{InferenceResponse, MetadataTensor, ModelMetadataV2}

class OnnxHttpV2Spec extends BaseHttpSpec {

  // The model is ONNX 1.3 from https://github.com/onnx/models/tree/master/vision/classification/mnist

  "The HTTP service V2 of serving ONNX" should {

    "return a prediction response for POST requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/infer" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val input0 = getResource("mnist_request_v2_0.json")
      Post(s"/v2/models/$name/versions/${deployResponse.version}/infer", HttpEntity.fromPath(`application/json`, input0)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        val actual = responseAs[InferenceResponse]
        val expected = loadJson[InferenceResponse](getResource("mnist_response_v2_0.json"))
        actual.dataToSeq.outputs.head.data shouldEqual expected.dataToSeq.outputs.head.data
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v2/models/${MODEL_NAME}/infer" in {
      val name = "an-onnx-model"
      deployModel(name, "mnist.onnx", `application/octet-stream`)

      val input0 = getResource("mnist_request_v2_0.json")
      Post(s"/v2/models/$name/infer", HttpEntity.fromPath(`application/json`, input0)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        val actual = responseAs[InferenceResponse]
        val expected = loadJson[InferenceResponse](getResource("mnist_response_v2_0.json"))
        actual.dataToSeq.outputs.head.data shouldEqual expected.dataToSeq.withModelSpec(name = name, version = None).outputs.head.data
      }

      undeployModel(name)
    }

    "return a model metadata response with specified version for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val expected = ModelMetadataV2(
        name = name,
        versions = Seq("1"),
        platform = "onnx_onnxv1",
        inputs = Seq(
          MetadataTensor(name = "Input3", datatype = "FP32", shape = Seq(1, 1, 28, 28))
        ),
        outputs = Seq(
          MetadataTensor(name = "Plus214_Output_0", datatype = "FP32", shape = Seq(1, 10)),
        )
      )

      Get(s"/v2/models/$name/versions/${deployResponse.version}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      Get(s"/v2/models/$name") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        log.info(responseAs[String])
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      undeployModel(name)
    }
  }

}
