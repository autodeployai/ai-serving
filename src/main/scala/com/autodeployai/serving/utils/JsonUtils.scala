/*
 * Copyright (c) 2019-2026 AutoDeployAI
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

package com.autodeployai.serving.utils

import ai.onnxruntime.platform.Fp16Conversions
import spray.json.{JsArray, JsBoolean, JsFalse, JsNull, JsNumber, JsObject, JsString, JsTrue, JsValue}

import scala.jdk.CollectionConverters._

object JsonUtils {

  def anyToJson(a: Any): JsValue = a match {
    case s: String              => JsString(s)
    case i: Int                 => JsNumber(i)
    case i: java.lang.Integer   => JsNumber(i)
    case l: Long                => JsNumber(l)
    case l: java.lang.Long      => JsNumber(l)
    case f: Float               => JsNumber(f)
    case f: java.lang.Float     => JsNumber(f.toFloat)
    case d: Double              => JsNumber(d)
    case d: java.lang.Double    => JsNumber(d)
    case s: Short               => JsNumber(s)
    case s: java.lang.Short     => JsNumber(s.toShort)
    case b: Byte                => JsNumber(b)
    case b: java.lang.Byte      => JsNumber(b.toByte)
    case n: java.lang.Number    => JsNumber(BigDecimal(n.toString))
    case true                   => JsTrue
    case false                  => JsFalse
    case s: Seq[_]              => seqToJson(s)
    case a: Array[_]            => arrayToJson(a)
    case l: java.util.List[_]   => seqToJson(l.asScala.toSeq)
    case m: Map[_, _]           => mapToJson(m)
    case m: java.util.Map[_, _] => mapToJson(m.asScala.toMap)
    case _                      => JsNull
  }

  def mapToJson(map: Map[_, _]): JsObject = {
    JsObject(map.map[String, JsValue](x => x._1.toString -> anyToJson(x._2)))
  }

  def seqToJson(s: Seq[_]): JsArray = {
    JsArray(s.map(anyToJson).toVector)
  }

  def arrayToJson(s: Array[_]): JsArray = {
    JsArray(s.map(anyToJson).toVector)
  }

  def jsonToAny(value: JsValue): Any = value match {
    case JsNumber(n)      => n
    case JsString(s)      => s
    case JsTrue           => true
    case JsFalse          => false
    case JsNull           => null
    case JsArray(values)  => values.map(jsonToAny)
    case JsObject(fields) => fields.map[String, Any](x => x._1 -> jsonToAny(x._2))
  }

  def jsonToBOOL(value: JsValue): Boolean = value match {
    case JsBoolean(b) => b
    case JsNumber(n)  => n != 0
    case JsString(s)  => {
      val sl = s.toLowerCase
      sl == "true" || sl == "1"
    }
    case _            => false
  }

  def jsonToINT8(value: JsValue): Byte = value match {
    case JsNumber(n)  => n.byteValue
    case JsString(s)  => java.lang.Byte.parseByte(s)
    case JsBoolean(b) => if (b) 1 else 0
    case _            => 0
  }

  def jsonToINT16(value: JsValue): Short = value match {
    case JsNumber(n)  => n.shortValue
    case JsString(s)  => java.lang.Short.parseShort(s)
    case JsBoolean(b) => if (b) 1 else 0
    case _            => 0
  }

  def jsonToINT32(value: JsValue): Int = value match {
    case JsNumber(n)  => n.bigDecimal.intValue
    case JsString(s)  => java.lang.Integer.parseInt(s)
    case JsBoolean(b) => if (b) 1 else 0
    case _            => 0
  }

  def jsonToINT64(value: JsValue): Long = value match {
    case JsNumber(n)  => n.bigDecimal.longValue
    case JsString(s)  => java.lang.Long.parseLong(s)
    case JsBoolean(b) => if (b) 1L else 0L
    case _            => 0L
  }

  def jsonToFP32(value: JsValue): Float = value match {
    case JsNumber(n)  => n.bigDecimal.floatValue()
    case JsNull       => Float.NaN
    case JsString(s)  => java.lang.Float.parseFloat(s)
    case JsBoolean(b) => if (b) 1.0f else 0.0f
    case _            => 0.0f
  }

  def jsonToFP64(value: JsValue): Double = value match {
    case JsNumber(n)  => n.bigDecimal.doubleValue()
    case JsNull       => Double.NaN
    case JsString(s)  => java.lang.Double.parseDouble(s)
    case JsBoolean(b) => if (b) 1.0 else 0.0
    case _            => 0.0
  }

  def jsonToString(value: JsValue): String = value match {
    case JsString(s)  => s
    case JsNumber(n)  => n.toString
    case JsBoolean(b) => b.toString
    case _            => ""
  }

  def jsArrayToFP32(value: JsArray, buffer: Array[Float], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToFP32(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToFP32(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToFP64(value: JsArray, buffer: Array[Double], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToFP64(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToFP64(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToINT64(value: JsArray, buffer: Array[Long], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToINT64(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToINT64(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToINT32(value: JsArray, buffer: Array[Int], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToINT32(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToINT32(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToINT16(value: JsArray, buffer: Array[Short], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToINT16(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToINT16(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToINT8(value: JsArray, buffer: Array[Byte], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToINT8(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToINT8(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToBOOL(value: JsArray, buffer: Array[Boolean], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToBOOL(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToBOOL(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToString(value: JsArray, buffer: Array[String], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToString(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          buffer(idx + i) = jsonToString(scalar)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToFP16(value: JsArray, buffer: Array[Short], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToFP16(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          val f = jsonToFP32(scalar)
          buffer(idx + i) = Fp16Conversions.floatToFp16(f)
          result += 1
      }
      i += 1
    }
    result
  }

  def jsArrayToBF16(value: JsArray, buffer: Array[Short], pos: Int): Int = {
    var result = 0
    var idx = pos

    val elements = value.elements
    val length = elements.size
    var i = 0
    while (i < length) {
      elements(i) match {
        case array: JsArray  =>
          val count = jsArrayToBF16(array, buffer, idx)
          idx += count
          result += count
        case scalar: JsValue =>
          val f = jsonToFP32(scalar)
          buffer(idx + i) = Fp16Conversions.floatToBf16(f)
          result += 1
      }
      i += 1
    }
    result
  }

}
