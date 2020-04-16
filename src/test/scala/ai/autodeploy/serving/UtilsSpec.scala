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

import ai.autodeploy.serving.utils.DataUtils._
import ai.autodeploy.serving.utils.Utils._
import org.scalatest.{Matchers, WordSpec}

class UtilsSpec extends WordSpec
  with Matchers {

  "Utils" can {

    "shapeOfValue" in {
      shapeOfValue(Vector(Vector(1, 2), Vector(3, 4), Vector(5, 6))) shouldEqual Array(3, 2)
      shapeOfValue(Vector(1, 2, 3)) shouldEqual Array(3)
    }
  }

  "DataUtils" can {

    "anyToFloat" in {
      anyToFloat(1.0) shouldEqual 1.0f
      anyToFloat(1) shouldEqual 1.0f
      anyToFloat(1.0f) shouldEqual 1.0f
      anyToFloat("1.0") shouldEqual 1.0f
    }
  }

}
