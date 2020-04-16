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

package ai.autodeploy.serving

import java.nio.file.Paths

import ai.autodeploy.serving.deploy.ModelManager
import ai.autodeploy.serving.errors.ErrorJsonSupport
import ai.autodeploy.serving.model.DeployResponse
import ai.autodeploy.serving.utils.Utils
import akka.http.scaladsl.model.{ContentType, HttpEntity, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.Outcome

abstract class BaseHttpSpec extends BaseSpec
  with ScalatestRouteTest
  with ErrorJsonSupport {

  val route = AIServer.route

  override def withFixture(test: NoArgTest): Outcome = {
    try {
      test()
    } finally {
      // Shared cleanup (run at end of each test)
      Utils.deleteDirectory(Paths.get(ModelManager.HOME_PATH))
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

  def undeployModel(name: String): Unit = {
    Delete(s"/v1/models/${name}") ~> route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

}
