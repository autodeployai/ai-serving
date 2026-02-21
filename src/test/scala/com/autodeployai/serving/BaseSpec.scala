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

import java.nio.ByteOrder
import java.nio.file.{Files, Path, Paths}
import akka.actor.ActorSystem
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.testkit._
import com.autodeployai.serving.model.JsonSupport
import com.autodeployai.serving.protobuf.TensorProto
import org.scalactic.{Equality, TolerantNumerics}
import org.scalatest.{Matchers, WordSpec}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._

abstract class BaseSpec extends WordSpec
  with Matchers
  with JsonSupport {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  // Increase time out only for debugging
  implicit def default(implicit system: ActorSystem): RouteTestTimeout = RouteTestTimeout(30.minutes dilated)

  val doubleTolerance = 0.1
  val floatTolerance = 0.1f
  val bigDecimalTolerance: BigDecimal = BigDecimal.valueOf(doubleTolerance)

  implicit val doubleEquality: Equality[Double] = TolerantNumerics.tolerantDoubleEquality(doubleTolerance)
  implicit val floatEquality: Equality[Float] = TolerantNumerics.tolerantFloatEquality(floatTolerance)
  implicit val bigDecimalEquality: Equality[BigDecimal] = {
    if (bigDecimalTolerance <= 0.0f)
      throw new IllegalArgumentException(bigDecimalTolerance.toString + " passed to tolerantFloatEquality was zero or negative. Must be a positive non-zero number.")
    new Equality[BigDecimal] {
      def areEqual(a: BigDecimal, b: Any): Boolean = {
        b match {
          case bDecimal: BigDecimal => (a <= bDecimal + bigDecimalTolerance) && (a >= bDecimal - bigDecimalTolerance)
          case bDouble: Double => (a <= bDouble + bigDecimalTolerance) && (a >= bDouble - bigDecimalTolerance)
          case bFloat: Float => (a <= bFloat + bigDecimalTolerance) && (a >= bFloat - bigDecimalTolerance)
          case _ => false
        }
      }
      override def toString: String = s"TolerantBigDecimalEquality($floatTolerance)"
    }
  }

  implicit val anyEquality: Equality[Any] = {
    new Equality[Any] {
      def areEqual(a: Any, b: Any): Boolean = {
        a match {
          case aFloat: Float    => floatEquality.areEqual(aFloat, b)
          case aDouble: Double  => doubleEquality.areEqual(aDouble, b)
          case aDecimal: BigDecimal => bigDecimalEquality.areEqual(aDecimal, b)
          case seq: Seq[Any]    => anySeqEquality.areEqual(seq, b)
          case _ => false
        }
      }
      override def toString: String = s"TolerantAnyEquality($floatTolerance)"
    }
  }

  def seqEquality[T]: Equality[Seq[T]] = {
    new Equality[Seq[T]] {
      def areEqual(a: Seq[T], b: Any): Boolean = {
        b match {
          case bFloat: Seq[_] =>
            if (a.size == bFloat.size) {
              a.zip(bFloat).forall(x => x._1 match {
                case d: Double => doubleEquality.areEqual(d, x._2)
                case f: Float  => floatEquality.areEqual(f, x._2)
                case b: BigDecimal => bigDecimalEquality.areEqual(b, x._2)
                case _ => false
              })
            } else false
          case _ => false
        }
      }
      override def toString: String = s"TolerantSeqEquality($floatTolerance)"
    }
  }

  implicit val floatSeqEquality: Equality[Seq[Float]] = seqEquality[Float]

  implicit val doubleSeqEquality: Equality[Seq[Double]] = seqEquality[Double]

  implicit val bigDecimalSeqEquality: Equality[Seq[BigDecimal]] = seqEquality[BigDecimal]

  implicit val anySeqEquality: Equality[Seq[Any]] = seqEquality[Any]

  def getResource(name: String): Path = {
    Paths.get(s"./src/test/resources/$name")
  }

  def getFloatTensor(name: String): Seq[Float] = {
    val outputTensor = TensorProto.parseFrom(Files.readAllBytes(getResource(name)))
    val buf = outputTensor.rawData.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val arr = Array.ofDim[Float](buf.capacity())
    buf.get(arr)
    arr.toSeq
  }
}
