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

import spray.json._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import com.autodeployai.serving.model.{DeployConfig, InferenceResponse, MetadataTensor, ModelMetadataV2, ModelReadyResponse, ResponseOutput}

class PmmlHttpV2Spec extends BaseHttpSpec {

  // The model is from http://dmg.org/pmml/pmml_examples/KNIME_PMML_4.1_Examples/single_iris_dectree.xml

  "The HTTP service V2 of serving PMML" should {

    "return a prediction response for POST requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/infer" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v2/models/${name}/versions/${deployResponse.version}/infer", HttpEntity(`application/json`,
        """{"id": "42",
          |"inputs": [
          |{"name": "sepal_length", "shape": [1], "datatype": "FP64", "data": [5.1]},
          |{"name": "sepal_width", "shape": [1], "datatype": "FP64", "data": [3.5]},
          |{"name": "petal_length", "shape": [1], "datatype": "FP64", "data": [1.4]},
          |{"name": "petal_width", "shape": [1], "datatype": "FP64", "data": [0.2]}
          |]
          |}""".stripMargin)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        val expected = InferenceResponse(
          model_name = name,
          model_version = Some(deployResponse.version),
          id = Some("42"),
          outputs = Seq(
            ResponseOutput(name = "predicted_class", shape = Seq(1), datatype = "BYTES", data = Array("Iris-setosa")),
            ResponseOutput(name = "probability", shape = Seq(1), datatype = "FP64", data = Array(1.0)),
            ResponseOutput(name = "probability_Iris-setosa", shape = Seq(1), datatype = "FP64", data = Array(1.0)),
            ResponseOutput(name = "probability_Iris-versicolor", shape = Seq(1), datatype = "FP64", data = Array(0.0)),
            ResponseOutput(name = "probability_Iris-virginica", shape = Seq(1), datatype = "FP64", data = Array(0.0)),
            ResponseOutput(name = "node_id", shape = Seq(1), datatype = "BYTES", data = Array("1"))
          )
        )
        responseAs[String] shouldEqual expected.toJson.toString
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/infer with specified outputs" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v2/models/${name}/versions/${deployResponse.version}/infer", HttpEntity(`application/json`,
        """{"id": "42",
          |"inputs": [
          |{"name": "sepal_length", "shape": [2], "datatype": "FP64", "data": [5.1, 7]},
          |{"name": "sepal_width", "shape": [2], "datatype": "FP64", "data": [3.5, 3.2]},
          |{"name": "petal_length", "shape": [2], "datatype": "FP64", "data": [1.4, 4.7]},
          |{"name": "petal_width", "shape": [2], "datatype": "FP64", "data": [0.2, 1.4]}
          |],
          |"outputs": [{"name": "predicted_class"}, {"name": "probability"}]
          |}""".stripMargin)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        val expected = InferenceResponse(
          model_name = name,
          model_version = Some(deployResponse.version),
          id = Some("42"),
          outputs = Seq(
            ResponseOutput(name = "predicted_class", shape = Seq(2), datatype = "BYTES", data = Array("Iris-setosa", "Iris-versicolor")),
            ResponseOutput(name = "probability", shape = Seq(2), datatype = "FP64", data = Array(1.0, 0.9074074074074074)),
          )
        )
        responseAs[String] shouldEqual expected.toJson.toString
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v2/models/${MODEL_NAME}/infer" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v2/models/${name}/infer", HttpEntity(`application/json`,
        """{"id": "42",
          |"inputs": [
          |{"name": "sepal_length", "shape": [2], "datatype": "FP64", "data": [5.1, 7]},
          |{"name": "sepal_width", "shape": [2], "datatype": "FP64", "data": [3.5, 3.2]},
          |{"name": "petal_length", "shape": [2], "datatype": "FP64", "data": [1.4, 4.7]},
          |{"name": "petal_width", "shape": [2], "datatype": "FP64", "data": [0.2, 1.4]}
          |]
          |}""".stripMargin)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        val expected = InferenceResponse(
          model_name = name,
          model_version = Option(deployResponse.version),
          id = Some("42"),
          outputs = Seq(
            ResponseOutput(name = "predicted_class", shape = Seq(2), datatype = "BYTES", data = Array("Iris-setosa", "Iris-versicolor")),
            ResponseOutput(name = "probability", shape = Seq(2), datatype = "FP64", data = Array(1.0, 0.9074074074074074)),
            ResponseOutput(name = "probability_Iris-setosa", shape = Seq(2), datatype = "FP64", data = Array(1.0, 0.0)),
            ResponseOutput(name = "probability_Iris-versicolor", shape = Seq(2), datatype = "FP64", data = Array(0.0, 0.9074074074074074)),
            ResponseOutput(name = "probability_Iris-virginica", shape = Seq(2), datatype = "FP64", data = Array(0.0, 0.09259259259259259)),
            ResponseOutput(name = "node_id", shape = Seq(2), datatype = "BYTES", data = Array("1", "3"))
          )
        )
        responseAs[String] shouldEqual expected.toJson.toString
      }

      undeployModel(name)
    }

    "return a model metadata response with specified version for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      val expected = ModelMetadataV2(
        name = "a-pmml-model",
        versions = Seq("1"),
        platform = "pmml_pmmlv4",
        inputs = Seq(
          MetadataTensor(name = "sepal_length", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "sepal_width", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "petal_length", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "petal_width", datatype = "FP64", shape = Seq(-1))
        ),
        outputs = Seq(
          MetadataTensor(name = "predicted_class", datatype = "BYTES", shape = Seq(-1)),
          MetadataTensor(name = "probability", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "probability_Iris-setosa", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "probability_Iris-versicolor", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "probability_Iris-virginica", datatype = "FP64", shape = Seq(-1)),
          MetadataTensor(name = "node_id", datatype = "BYTES", shape = Seq(-1))
        )
      )

      Get(s"/v2/models/${name}/versions/${deployResponse.version}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      Get(s"/v2/models/${name}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelMetadataV2] shouldEqual expected
      }

      undeployModel(name)
    }

    "return a 404 error with a model that does not exist for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}" in {
      Get(s"/v2/models/not-exist-model/versions/1") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }

      Get(s"/v2/models/not-exist-model") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "return an OK status response for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/ready" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)
      val deployResponse2 = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v2/models/${name}/versions/${deployResponse.version}/ready") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelReadyResponse] shouldEqual ModelReadyResponse(true)
      }

      Get(s"/v2/models/${name}/versions/${deployResponse2.version}/ready") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelReadyResponse] shouldEqual ModelReadyResponse(true)
      }

      undeployModel(name)
    }

    "return an OK status response for GET requests to /v2/models/${MODEL_NAME}/ready" in {
      val name = "a-pmml-model"
      deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v2/models/${name}/ready") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelReadyResponse] shouldEqual ModelReadyResponse(true)
      }

      undeployModel(name)
    }

    "return a 404 error with a model that does not exist for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/ready" in {
      Get(s"/v2/models/not-exist-model/versions/1/ready") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }

      Get(s"/v2/models/not-exist-model/ready") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "return a 404 error with a version of model that does not exist for GET requests to /v2/models/${MODEL_NAME}/versions/${MODEL_VERSION}/ready" in {
      val name = "a-pmml-model"
      deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v2/models/${name}/versions/2/ready") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
      }

      Get(s"/v2/models/${name}/ready") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }

      undeployModel(name)
    }

    "return a 504 error response with small timeout enabled" in {
      val name = "a-large-pmml-model"
      val deployConfig = DeployConfig(requestTimeoutMs=Some(1))
      val deployResponse = deployModelWithConfig(name, "xgb-iris.pmml", `text/xml(UTF-8)`, deployConfig)

      val nLoop = 3
      for (_ <- 0 until nLoop) {
        Post(s"/v2/models/${name}/infer", HttpEntity(`application/json`,
          """{"id": "42",
            |"inputs": [
            |{"name": "sepal_length", "shape": [3], "datatype": "FP64", "data": [5.1, 7, 5.8]},
            |{"name": "sepal_width", "shape": [3], "datatype": "FP64", "data": [3.5, 3.2, 2.7]},
            |{"name": "petal_length", "shape": [3], "datatype": "FP64", "data": [1.4, 4.7, 5.1]},
            |{"name": "petal_width", "shape": [3], "datatype": "FP64", "data": [0.2, 1.4, 1.9]}
            |]
            |}""".stripMargin)) ~>
          addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
            if (status == StatusCodes.OK) {
              log.warn("Your computer is more powerful, please send more rows to reproduce timeout")
            } else {
              status shouldEqual StatusCodes.GatewayTimeout
              val actual = responseAs[com.autodeployai.serving.errors.Error]
              actual.error.contains(s"A request to $name:${deployResponse.version} exceeded timeout") shouldEqual true
            }
        }
      }
      undeployModel(name)
    }
  }

}
