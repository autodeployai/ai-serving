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

import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.util.ByteString
import com.autodeployai.serving.errors.Error

class ServerSpec extends BaseHttpSpec {

  "The HTTP service" should {

    "return an OK status response for GET requests to /up" in {
      Get("/up") ~> route ~> check {
        responseAs[String] shouldEqual """OK"""
      }
    }

    "return a 404 error with a model that does not exist for GET requests to /v1/models/not-exist-model " in {
      Get("/v1/models/not-exist-model") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error] shouldEqual Error("Model 'not-exist-model' not found")
      }
    }

    "return a 400 error for POST requests to /v1/models/not-exist-model without header field Content-Type" in {
      Post("/v1/models/not-exist-model", HttpEntity(ContentTypes.NoContentType, ByteString("""{"X": [{}]}""".getBytes))) ~> route ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[Error] shouldEqual Error("Prediction request takes unknown content type: none/none")
      }
    }

    "return a 404 error with a model that does not exist for POST requests to /v1/models/not-exist-model " in {
      Post("/v1/models/not-exist-model", HttpEntity(`application/json`, """{"X": [{}]}""")) ~>
        addHeader(RawHeader("Content-Type", "application/json")) ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error] shouldEqual Error("Model 'not-exist-model' not found")
      }
    }

    "return a 404 error with a model that does not exist for DELETE requests to /v1/models/not-exist-model " in {
      Delete("/v1/models/not-exist-model") ~> route ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Error] shouldEqual Error("Model 'not-exist-model' not found")
      }
    }

  }
}