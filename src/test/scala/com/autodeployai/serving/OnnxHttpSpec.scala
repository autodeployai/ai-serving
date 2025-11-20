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

package com.autodeployai.serving

import java.nio.file.Files
import protobuf.Value.Kind
import protobuf.{ListValue, Record, TensorProto, Value}
import akka.http.scaladsl.model.ContentTypes.{`application/json`, `application/octet-stream`}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.util.ByteString
import com.autodeployai.serving.model.{ModelInfo, PredictResponse}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class OnnxHttpSpec extends BaseHttpSpec {

  // The model is ONNX 1.3 from https://github.com/onnx/models/tree/master/vision/classification/mnist
  // NOTE: Test cases are disabled by default because users need to configure libraries of Onnx Runtime before running them
  // You can remove the Ignore annotation above the test Class to free all them all

  "The HTTP service of serving ONNX" should {

    "return a validation response for POST requests to /v1/validate" in {
      val path = getResource("mnist.onnx")
      Post("/v1/validate", HttpEntity.fromPath(`application/octet-stream`, path)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelInfo] shouldBe loadJson[ModelInfo](getResource("mnist.json"))
      }
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using json payload in records: list like [{column -> value}, … , {column -> value}]" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val input0 = getResource("mnist_request_0.json")
      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity.fromPath(`application/json`, input0)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[PredictResponse] shouldEqual loadJson[PredictResponse](getResource("mnist_response_0.json"))
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using json payload in split: dict like {‘columns’ -> [columns], ‘data’ -> [values]}" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val input3 = getResource("mnist_request_3.json")
      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity.fromPath(`application/json`, input3)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[PredictResponse] shouldEqual loadJson[PredictResponse](getResource("mnist_response_3.json"))
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using binary protobuf payload in records: list like [{column -> value}, … , {column -> value}]" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))
      val input1 = protobuf.PredictRequest().withX(
        protobuf.RecordSpec(records = List(Record(Map("Input3" -> Value(Kind.TensorValue(tensor0)))))))

      // Save the PredictRequest message to file
      // Files.write(getResource("mnist_request_0.pb"), input1.toByteArray)

      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity(`application/octet-stream`, input1.toByteArray)) ~>
        addHeader(RawHeader("Content-Type", "application/octet-stream")) ~> route ~> check {
        status shouldEqual StatusCodes.OK

        val result: Future[ByteString] = response.entity.dataBytes.runFold(ByteString.empty) { case (acc, b) => acc ++ b }
        result.onComplete {
          case Success(value)     => {
            // Save the PredictResponse message to file
            // Files.write(getResource("mnist_response_0.pb"), value.toArray)

            val predictResponse = protobuf.PredictResponse.parseFrom(value.toArray)
            val result = predictResponse.result.get.records.head.fields.values.head.getTensorValue.floatData

            val expected = getFloatTensor("mnist_output_0.pb")
            result should have size expected.size
            result.zip(expected).foreach { x =>
              x._1 shouldEqual x._2
            }
          }
          case Failure(exception) => throw exception
        }
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using binary protobuf payload in split: dict like {‘columns’ -> [columns], ‘data’ -> [values]}" in {
      val name = "an-onnx-model"
      val deployResponse = deployModel(name, "mnist.onnx", `application/octet-stream`)

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))
      val tensor1 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_1.pb")))
      val tensor2 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_2.pb")))
      val input3 = protobuf.PredictRequest().withX(
        protobuf.RecordSpec(columns = Seq("Input3"), data = Seq(
          ListValue(Seq(Value(Kind.TensorValue(tensor0)))),
          ListValue(Seq(Value(Kind.TensorValue(tensor1)))),
          ListValue(Seq(Value(Kind.TensorValue(tensor2))))
        ))
      )

      // Save the PredictRequest message to file
      // Files.write(getResource("mnist_request_3.pb"), input3.toByteArray)

      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity(`application/octet-stream`, input3.toByteArray)) ~>
        addHeader(RawHeader("Content-Type", "application/octet-stream")) ~> route ~> check {
        status shouldEqual StatusCodes.OK

        val result: Future[ByteString] = response.entity.dataBytes.runFold(ByteString.empty) { case (acc, b) => acc ++ b }
        result.onComplete {
          case Success(value)     => {
            // Save the PredictResponse message to file
            // Files.write(getResource("mnist_response_3.pb"), value.toArray)

            val predictResponse = protobuf.PredictResponse.parseFrom(value.toArray)

            (0 until 3).foreach { i =>
              val result = predictResponse.result.get.data(i).values.head.getTensorValue.floatData
              val expected = getFloatTensor(s"mnist_output_${i}.pb")
              result should have size expected.size
              result.zip(expected).foreach { x =>
                x._1 shouldEqual x._2
              }
            }
          }
          case Failure(exception) => throw exception
        }
      }

      undeployModel(name)
    }
  }

}
