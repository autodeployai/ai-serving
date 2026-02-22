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

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import com.autodeployai.serving.model.{DeployConfig, InferenceRequest, ModelInfo, ModelMetadata, PredictResponse, RecordSpec, RequestInput}

class PmmlHttpSpec extends BaseHttpSpec {

  // The model is from http://dmg.org/pmml/pmml_examples/KNIME_PMML_4.1_Examples/single_iris_dectree.xml

  "The HTTP service of serving PMML" should {

    "return a validation response for POST requests to /v1/validate" in {
      val path = getResource("single_iris_dectree.xml")
      Post("/v1/validate", HttpEntity.fromPath(`text/xml(UTF-8)`, path)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelInfo] shouldBe loadJson[ModelInfo](getResource("single_iris_dectree.json"))
      }
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using payload in records: list like [{column -> value}, … , {column -> value}]" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity(`application/json`,
        """{"X": [{"sepal_length": 5.1, "sepal_width": 3.5, "petal_length": 1.4, "petal_width": 0.2}]}""")) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        responseAs[PredictResponse] shouldEqual PredictResponse(RecordSpec(
          records = Some(List(Map("node_id" -> "1",
            "probability_Iris-setosa" -> 1.0,
            "predicted_class" -> "Iris-setosa",
            "probability_Iris-virginica" -> 0.0,
            "probability_Iris-versicolor" -> 0.0,
            "probability" -> 1.0))))
        )
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME} " +
      "using payload in split: dict like {‘columns’ -> [columns], ‘data’ -> [values]}" in {
      val name = "a-pmml-model"
      deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v1/models/${name}", HttpEntity(`application/json`,
        """{"X": {"columns": ["sepal_length", "sepal_width", "petal_length", "petal_width"],
          |"data":[[5.1, 3.5, 1.4, 0.2], [7, 3.2, 4.7, 1.4]]}}""".stripMargin)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        responseAs[PredictResponse] shouldEqual PredictResponse(RecordSpec(
          columns = Some(List("predicted_class", "probability", "probability_Iris-setosa", "probability_Iris-versicolor",
            "probability_Iris-virginica", "node_id")),
          data = Some(List(List("Iris-setosa", 1.0, 1.0, 0.0, 0.0, "1"),
            List("Iris-versicolor", 0.9074074074074074, 0.0, 0.9074074074074074, 0.09259259259259259, "3")))))
      }

      undeployModel(name)
    }

    "return a prediction response for POST requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION} " +
      "using payload in records: list like [{column -> value}, … , {column -> value}] with output filter" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Post(s"/v1/models/${name}/versions/${deployResponse.version}", HttpEntity(`application/json`,
        """{"X": [{"sepal_length": 5.1, "sepal_width": 3.5, "petal_length": 1.4, "petal_width": 0.2}],
          |"filter": ["predicted_class"]}""".stripMargin)) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        responseAs[PredictResponse] shouldEqual PredictResponse(RecordSpec(
          records = Some(List(Map("predicted_class" -> "Iris-setosa"))))
        )
      }

      undeployModel(name)
    }

    "return a model metadata response with specified version for GET requests to /v1/models/${MODEL_NAME}/versions/${MODEL_VERSION}" in {
      val name = "a-pmml-model"
      val deployResponse = deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v1/models/${name}/versions/${deployResponse.version}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelMetadata].versions.get should have size 1
      }

      undeployModel(name)
    }


    "return a model metadata response with all versions for GET requests to /v1/models/${MODEL_NAME}" in {
      val name = "a-pmml-model"
      deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)
      deployModel(name, "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v1/models/${name}") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ModelMetadata].versions.get should have size 2
      }

      undeployModel(name)
    }

    "return all models metadata response for GET requests to /v1/models" in {
      deployModel("a-pmml-model", "single_iris_dectree.xml", `text/xml(UTF-8)`)
      deployModel("b-pmml-model", "single_iris_dectree.xml", `text/xml(UTF-8)`)

      Get(s"/v1/models") ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[List[ModelMetadata]] should have size 2
      }

      undeployModel("a-pmml-model")
      undeployModel("b-pmml-model")
    }

    "deploy a PMML model with warmup enabled using zero data" in {
      val name = "a-pmml-model"
      val deployConfig = DeployConfig(warmupCount=Some(100), warmupDataType=Some("zero"))
      deployModelWithConfig(name, "single_iris_dectree.xml", `text/xml(UTF-8)`, deployConfig)
      undeployModel(name)
    }

    "deploy a PMML model with warmup enabled using random data" in {
      val name = "a-pmml-model"
      val deployConfig = DeployConfig(warmupCount=Some(100), warmupDataType=Some("random"))
      deployModelWithConfig(name, "single_iris_dectree.xml", `text/xml(UTF-8)`, deployConfig)
      undeployModel(name)
    }

    "return a 504 error response with small timeout enabled" in {
      val name = "a-large-pmml-model"
      val deployConfig = DeployConfig(requestTimeoutMs=Some(1))
      val deployResponse = deployModelWithConfig(name, "xgb-iris.pmml", `text/xml(UTF-8)`, deployConfig)

      val nLoop = 3
      for (_ <- 0 until nLoop) {
        Post(s"/v1/models/${name}", HttpEntity(`application/json`,
          """{"X": {"columns": ["sepal_length", "sepal_width", "petal_length", "petal_width"],
            |"data":[[5.1, 3.5, 1.4, 0.2], [7, 3.2, 4.7, 1.4], [5.8, 2.7, 5.1, 1.9]]}}""".stripMargin)) ~>
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
