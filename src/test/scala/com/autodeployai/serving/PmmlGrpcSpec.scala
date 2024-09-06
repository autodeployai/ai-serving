/*
 * Copyright (c) 2019-2020 AutoDeployAI
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

class PmmlGrpcSpec extends BaseGrpcSpec {

  // The model is from http://dmg.org/pmml/pmml_examples/KNIME_PMML_4.1_Examples/single_iris_dectree.xml

  "The GRPC service" should {

    "return a validation response for calling 'validate'" in {
      val input = getResource("single_iris_dectree.xml")
      val output = getResource("single_iris_dectree.json")

      val response = blockingStub().validate(ValidateRequest(ByteString.copyFrom(Files.readAllBytes(input)), "PMML"))
      response shouldBe loadJson[ModelInfo](output).toPb
    }

    "return a prediction response for calling 'predict' using payload in records: list like [{column -> value}, … , {column -> value}]" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))

      val predictResponse = blockingStub().predict(PredictRequest(modelSpec = deployResponse.modelSpec).withX(
        RecordSpec(Seq(Record(Map("sepal_length" -> Value(Kind.NumberValue(5.1)),
          "sepal_width" -> Value(Kind.NumberValue(3.5)),
          "petal_length" -> Value(Kind.NumberValue(1.4)),
          "petal_width" -> Value(Kind.NumberValue(0.2))))))
      ))
      predictResponse shouldEqual PredictResponse(modelSpec = deployResponse.modelSpec).withResult(
        RecordSpec(Vector(Record(Map("node_id" -> Value(Kind.StringValue("1")),
          "probability_Iris-setosa" -> Value(Kind.NumberValue(1.0)),
          "predicted_class" -> Value(Kind.StringValue("Iris-setosa")),
          "probability_Iris-virginica" -> Value(Kind.NumberValue(0.0)),
          "probability_Iris-versicolor" -> Value(Kind.NumberValue(0.0)),
          "probability" -> Value(Kind.NumberValue(1.0))))))
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a prediction response for calling 'predict' using payload in split: dict like {‘columns’ -> [columns], ‘data’ -> [values]}" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))

      val predictResponse = blockingStub().predict(PredictRequest(modelSpec = Some(ModelSpec(name))).withX(
        RecordSpec(
          columns = Seq("sepal_length", "sepal_width", "petal_length", "petal_width"),
          data = Seq(ListValue(Seq(Value(Kind.NumberValue(5.1)), Value(Kind.NumberValue(3.5)),
            Value(Kind.NumberValue(1.4)), Value(Kind.NumberValue(0.2)))),
            ListValue(Seq(Value(Kind.NumberValue(7.0)), Value(Kind.NumberValue(3.2)), Value(Kind.NumberValue(4.7)),
              Value(Kind.NumberValue(1.4))))))
      ))
      predictResponse shouldEqual PredictResponse(modelSpec = Some(ModelSpec(name))).withResult(
        RecordSpec(
          columns = Vector("predicted_class", "probability", "probability_Iris-setosa", "probability_Iris-versicolor",
            "probability_Iris-virginica", "node_id"),
          data = Vector(ListValue(Seq(Value(Kind.StringValue("Iris-setosa")), Value(Kind.NumberValue(1.0)),
            Value(Kind.NumberValue(1.0)), Value(Kind.NumberValue(0.0)), Value(Kind.NumberValue(0.0)), Value(Kind.StringValue("1")))),
            ListValue(Seq(Value(Kind.StringValue("Iris-versicolor")), Value(Kind.NumberValue(0.9074074074074074)),
              Value(Kind.NumberValue(0.0)), Value(Kind.NumberValue(0.9074074074074074)),
              Value(Kind.NumberValue(0.09259259259259259)), Value(Kind.StringValue("3")))))
        )
      )

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'loadModelMetadataWithVersion' with the specified model and version" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      val deployResponse = blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))

      val metadataResponse = blockingStub().getModelMetadata(GetModelMetadataRequest(deployResponse.modelSpec))
      metadataResponse.modelSpec shouldEqual deployResponse.modelSpec
      metadataResponse.metadata should have size 1
      metadataResponse.metadata.head.versions should have size 1

      blockingStub().undeploy(UndeployRequest(deployResponse.modelSpec))
    }

    "return a model metadata response for calling 'loadModelMetadataWithVersion' with the specified model" in {
      val name = "a-pmml-model"
      val input = getResource("single_iris_dectree.xml")
      blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))
      blockingStub().deploy(DeployRequest(name, ByteString.copyFrom(Files.readAllBytes(input))))

      val modelSpec = Some(ModelSpec(name))
      val metadataResponse = blockingStub().getModelMetadata(GetModelMetadataRequest(modelSpec))
      metadataResponse.modelSpec shouldEqual modelSpec
      metadataResponse.metadata should have size 1
      metadataResponse.metadata.head.versions should have size 2

      blockingStub().undeploy(UndeployRequest(modelSpec))
    }

    "return all models metadata response for calling 'loadModelMetadataWithVersion' without a model" in {
      val input = getResource("single_iris_dectree.xml")
      blockingStub().deploy(DeployRequest("a-pmml-model", ByteString.copyFrom(Files.readAllBytes(input))))
      blockingStub().deploy(DeployRequest("b-pmml-model", ByteString.copyFrom(Files.readAllBytes(input))))

      val metadataResponse = blockingStub().getModelMetadata(GetModelMetadataRequest(None))
      metadataResponse.modelSpec shouldEqual None
      metadataResponse.metadata should have size 2
      metadataResponse.metadata.head.versions should have size 1

      blockingStub().undeploy(UndeployRequest(Some(ModelSpec("a-pmml-model"))))
      blockingStub().undeploy(UndeployRequest(Some(ModelSpec("b-pmml-model"))))
    }
  }

}
