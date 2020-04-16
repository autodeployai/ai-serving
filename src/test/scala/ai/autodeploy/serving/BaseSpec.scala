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

import java.nio.ByteOrder
import java.nio.file.{Files, Path, Paths}

import ai.autodeploy.serving.model.JsonSupport
import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.testkit._
import org.scalactic.TolerantNumerics
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._

abstract class BaseSpec extends WordSpec
  with Matchers
  with JsonSupport {

  // Increase time out only for debugging
  implicit def default(implicit system: ActorSystem) = RouteTestTimeout(30.minutes dilated)

  implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)
  implicit val floatEquality = TolerantNumerics.tolerantFloatEquality(0.01f)

  def getResource(name: String): Path = {
    Paths.get(s"./src/test/resources/${name}")
  }

  def getFloatTensor(name: String): Seq[Float] = {
    val outputTensor = onnx.TensorProto.parseFrom(Files.readAllBytes(getResource(name)))
    val buf = outputTensor.rawData.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val arr = Array.ofDim[Float](buf.capacity())
    buf.get(arr)
    arr.toSeq
  }
}
