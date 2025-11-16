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

package com.autodeployai.serving.utils

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.util.Try

object DataUtils {

  def anyToFloat(value: Any): Float = value match {
    case d: Number => d.floatValue
    case _         => Try(value.toString.toFloat).getOrElse(0.0f)
  }

  def anyToDouble(value: Any): Double = value match {
    case d: Number => d.doubleValue
    case _         => Try(value.toString.toDouble).getOrElse(0.0)
  }

  def anyToByte(value: Any): Byte = value match {
    case d: Number => d.byteValue
    case _         => Try(value.toString.toByte).getOrElse(0)
  }

  def anyToShort(value: Any): Short = value match {
    case d: Number => d.shortValue
    case _         => Try(value.toString.toShort).getOrElse(0)
  }

  def anyToInt(value: Any): Int = value match {
    case d: Number => d.intValue
    case _         => Try(value.toString.toInt).getOrElse(0)
  }

  def anyToLong(value: Any): Long = value match {
    case d: Number => d.longValue
    case _         => Try(value.toString.toLong).getOrElse(0)
  }

  def anyToBoolean(value: Any): Boolean = value match {
    case b: Boolean => b
    case _          => Try(value.toString.toBoolean).getOrElse(false)
  }

  def anyToString(value: Any): String = value match {
    case s: String => s
    case _         => Try(value.toString).getOrElse("")
  }

  def readBinaryString(buffer: ByteBuffer): Array[String] = {
    val builder = mutable.ArrayBuilder.make[String]
    buffer.position(0)
    while (buffer.hasRemaining) {
      val len = buffer.getInt()
      val pos = buffer.position()
      val subBuffer = buffer.slice(pos, len)
      val str = StandardCharsets.UTF_8.decode(subBuffer).toString
      buffer.position(pos + len)
      builder += str
    }
    builder.result()
  }

  def writeBinaryString(array: Array[String]): ByteBuffer = {
    val buffer = new ByteArrayOutputStream()
    val len = array.length
    var i = 0

    val writeBuffer = new Array[Byte](4)
    while (i < len) {
      val str = array(i)
      val bytes = str.getBytes(StandardCharsets.UTF_8)
      val v = bytes.length

      // Write size first
      writeBuffer(0) = (v >>> 24).toByte
      writeBuffer(1) = (v >>> 16).toByte
      writeBuffer(2) = (v >>> 8).toByte
      writeBuffer(3) = (v >>> 0).toByte
      buffer.write(writeBuffer)

      // Write content then
      buffer.write(bytes)

      i += 1
    }
    ByteBuffer.wrap(buffer.toByteArray).asReadOnlyBuffer()
  }


}
