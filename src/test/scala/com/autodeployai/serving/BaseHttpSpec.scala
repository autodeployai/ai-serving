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

import java.nio.file.Paths
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.errors.ErrorJsonSupport
import com.autodeployai.serving.model.{DeployConfig, DeployResponse}
import com.autodeployai.serving.utils.Utils
import org.scalatest.Outcome
import spray.json._

abstract class BaseHttpSpec extends BaseSpec
  with ScalatestRouteTest
  with ErrorJsonSupport {

  val route = AIServer.route

  override def withFixture(test: NoArgTest): Outcome = {
    try {
      test()
    } finally {
      // Shared cleanup (run at end of each test)
      Utils.deleteDirectory(Paths.get(InferenceService.homePath))
    }
  }

  def deployModel(name: String, filename: String, contentType: ContentType): DeployResponse = {
    var result: DeployResponse = null
    val path = getResource(filename)

    Put(s"/v1/models/${name}", HttpEntity.fromPath(contentType, path)) ~> route ~> check {
      status shouldEqual StatusCodes.Created
      result = responseAs[DeployResponse]
    }
    result
  }

  def deployModelWithConfig(name: String, filename: String, contentType: ContentType, deployConfig: DeployConfig): DeployResponse = {
    var result: DeployResponse = null
    val path = getResource(filename)

    val filePart = Multipart.FormData.BodyPart.fromPath(
      "model",
      contentType,
      path
    )

    val configPart = Multipart.FormData.BodyPart.Strict(
      "config",
      HttpEntity(ContentTypes.`application/json`, deployConfig.toJson.toString())
    )

    val formData = Multipart.FormData(Source(List(filePart, configPart)))
    Put(s"/v1/models/${name}", formData) ~> route ~> check {
      status shouldEqual StatusCodes.Created
      result = responseAs[DeployResponse]
    }
    result
  }

  def undeployModel(name: String): Unit = {
    Delete(s"/v1/models/${name}") ~> route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

}
