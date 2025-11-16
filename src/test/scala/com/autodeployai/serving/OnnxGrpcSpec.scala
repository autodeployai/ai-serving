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
import protobuf._
import com.autodeployai.serving.model.ModelInfo
import com.google.protobuf.ByteString

class OnnxGrpcSpec extends BaseGrpcSpec {

  // The model is ONNX 1.3 from https://github.com/onnx/models/tree/master/vision/classification/mnist
  // NOTE: Test cases are disabled by default because users need to configure libraries of Onnx Runtime before running them
  // You can remove the Ignore annotation above the test Class to free all them all

  "The GRPC service" should {

    "return a validation response for calling 'validate'" in {
      val input = getResource("mnist.onnx")
      val output = getResource("mnist.json")

      val response = blockingStub().validate(ValidateRequest(ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))
      response shouldBe loadJson[ModelInfo](output).toPb
    }

    "return a prediction response for calling 'predict' using payload in records: list like [{column -> value}, … , {column -> value}]" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))
      val predictResponse = blockingStub().predict(PredictRequest(deployResponse.modelSpec).withX(
        RecordSpec(records = List(Record(Map("Input3" -> Value(Kind.TensorValue(tensor0))))))))

      val result = predictResponse.result.get.records.head.fields.values.head.getTensorValue.floatData
      val expected = getFloatTensor("mnist_output_0.pb")
      result should have size expected.size
      result.zip(expected).foreach { x =>
        x._1 shouldEqual x._2
      }

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'predict' using payload in split: dict like {‘columns’ -> [columns], ‘data’ -> [values]}" in {
      val name = "an-onnx-model"
      val input = getResource("mnist.onnx")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input)), "ONNX"))

      val tensor0 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_0.pb")))
      val tensor1 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_1.pb")))
      val tensor2 = TensorProto.parseFrom(Files.readAllBytes(getResource("mnist_input_2.pb")))

      val predictResponse = blockingStub().predict(PredictRequest(deployResponse.modelSpec).withX(
        RecordSpec(
          columns = Seq("Input3"),
          data = Seq(ListValue(Seq(Value(Kind.TensorValue(tensor0)))),
            ListValue(Seq(Value(Kind.TensorValue(tensor1)))),
            ListValue(Seq(Value(Kind.TensorValue(tensor2))))
          ))))

      (0 until 3).foreach { i =>
        val result = predictResponse.result.get.data(i).values.head.getTensorValue.floatData
        val expected = getFloatTensor(s"mnist_output_${i}.pb")
        result should have size expected.size
        result.zip(expected).foreach { x =>
          x._1 shouldEqual x._2
        }
      }

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

  }
}
