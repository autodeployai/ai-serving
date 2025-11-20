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

import akka.http.scaladsl.model.StatusCodes
import com.autodeployai.serving.model.ServerMetadataResponse

class ServerV2Spec extends BaseHttpSpec {

  "The Sever HTTP service V2" should {

    "return an OK status response for GET requests to /v2/health/live" in {
      Get("/v2/health/live") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "return an OK status response for GET requests to /v2/health/ready" in {
      Get("/v2/health/ready") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "return an OK status response for GET requests to /v2" in {
      Get("/v2") ~> route ~> check {
        responseAs[ServerMetadataResponse].name shouldEqual "ai-serving"
      }
    }
  }
}