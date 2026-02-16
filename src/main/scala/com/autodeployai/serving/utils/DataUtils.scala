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
import com.autodeployai.serving.model.DataType
import com.autodeployai.serving.model.DataType.DataType
import com.google.protobuf.ByteString

import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder, DoubleBuffer, FloatBuffer, IntBuffer, LongBuffer, ShortBuffer}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.util.Try

object DataUtils {
  val TRUE_BYTE: Byte = 1.toByte
  val FALSE_BYTE: Byte = 0.toByte

  def anyToFloat(value: Any): Float = value match {
    case f: Float  => f
    case d: Double => d.floatValue
    case i: Int    => i.floatValue
    case l: Long   => l.floatValue
    case s: Short  => s.floatValue
    case b: Byte   => b.floatValue
    case b: Boolean => if (b) 1.0f else 0.0f
    case d: Number => d.floatValue
    case _         => Try(value.toString.toFloat).getOrElse(0.0f)
  }

  def anyToDouble(value: Any): Double = value match {
    case d: Double => d
    case f: Float  => f.doubleValue
    case i: Int    => i.doubleValue
    case l: Long   => l.doubleValue
    case s: Short  => s.doubleValue
    case b: Byte   => b.doubleValue
    case b: Boolean => if (b) 1.0 else 0.0
    case d: Number => d.doubleValue
    case _         => Try(value.toString.toDouble).getOrElse(0.0)
  }

  def anyToByte(value: Any): Byte = value match {
    case b: Byte   => b
    case i: Int    => i.byteValue
    case l: Long   => l.byteValue
    case s: Short  => s.byteValue
    case d: Double => d.byteValue
    case f: Float  => f.byteValue
    case b: Boolean => if (b) 1 else 0
    case d: Number => d.byteValue
    case _         => Try(value.toString.toByte).getOrElse(0)
  }

  def anyToShort(value: Any): Short = value match {
    case s: Short  => s
    case i: Int    => i.shortValue
    case b: Byte   => b.shortValue
    case l: Long   => l.shortValue
    case d: Double => d.shortValue
    case f: Float  => f.shortValue
    case b: Boolean => if (b) 1 else 0
    case d: Number => d.shortValue
    case _         => Try(value.toString.toShort).getOrElse(0)
  }

  def anyToInt(value: Any): Int = value match {
    case i: Int    => i
    case s: Short  => s.intValue
    case b: Byte   => b.intValue
    case l: Long   => l.intValue
    case d: Double => d.intValue
    case f: Float  => f.intValue
    case b: Boolean => if (b) 1 else 0
    case d: Number => d.intValue
    case _         => Try(value.toString.toInt).getOrElse(0)
  }

  def anyToLong(value: Any): Long = value match {
    case l: Long   => l
    case i: Int    => i.longValue
    case s: Short  => s.longValue
    case b: Byte   => b.longValue
    case d: Double => d.longValue
    case f: Float  => f.longValue
    case b: Boolean => if (b) 1L else 0L
    case d: Number => d.longValue
    case _         => Try(value.toString.toLong).getOrElse(0)
  }

  def anyToBoolean(value: Any): Boolean = value match {
    case b: Boolean => b
    case l: Long   => l == 1L
    case i: Int    => i == 1
    case s: Short  => s == 1
    case b: Byte   => b == 1
    case d: Double => d == 1.0
    case f: Float  => f == 1.0f
    case d: Number => d.intValue == 1
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

  def writeBinaryString(array: Array[String]): ByteString = {
    ByteString.copyFrom(convertToByteBuffer(array))
  }

  def convertToByteBuffer(array: Array[String]): ByteBuffer = {
    ByteBuffer.wrap(convertToByteArray(array))
  }

  def convertToByteArray(array: Array[String]): Array[Byte] = {
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
    buffer.toByteArray
  }

  def convertToByteBuffer(array: Array[Double]): ByteBuffer = {
    val byteBuffer = ByteBuffer.allocate(array.length * java.lang.Double.BYTES)
    byteBuffer.order(ByteOrder.nativeOrder)

    var i = 0
    while (i < array.length) {
      byteBuffer.putDouble(array(i))
      i += 1
    }
    byteBuffer
  }

  def convertToByteArray(array: Array[Double]): Array[Byte] = {
    convertToByteBuffer(array).array
  }

  def convertToByteBuffer(array: Array[Boolean]): ByteBuffer = {
    val byteArray = new Array[Byte](array.length)

    var i = 0
    while (i < array.length) {
      byteArray(i) = if (array(i)) TRUE_BYTE else FALSE_BYTE
      i += 1
    }
    ByteBuffer.wrap(byteArray)
  }

  def convertToByteArray(array: Array[Boolean]): Array[Byte] = {
    convertToByteBuffer(array).array()
  }

  def convertToByteBuffer(array: Array[Long]): ByteBuffer = {
    val byteBuffer = ByteBuffer.allocate(array.length * java.lang.Long.BYTES)
    byteBuffer.order(ByteOrder.nativeOrder)

    var i = 0
    while (i < array.length) {
      byteBuffer.putLong(array(i))
      i += 1
    }
    byteBuffer
  }

  def convertToByteArray(array: Array[Long]): Array[Byte] = {
    convertToByteBuffer(array).array
  }

  def convertToBooleanArray(array: Array[Byte]): Array[Boolean] = {
    val result = new Array[Boolean](array.length)
    var i = 0

    while (i < array.length) {
      result(i) = if (array(i) != FALSE_BYTE) true else false
      i += 1
    }
    result
  }

  def convertToArray(buffer: ByteBuffer, datatype: DataType): Array[_] = datatype match {
    case DataType.FP32 =>
      val buf = buffer.asFloatBuffer()
      val output = FloatBuffer.allocate(buf.capacity)
      output.put(buf)
      output.rewind
      output.array()
    case DataType.FP64 =>
      val buf = buffer.asDoubleBuffer()
      val output = DoubleBuffer.allocate(buf.capacity)
      output.put(buf)
      output.rewind
      output.array()
    case DataType.INT64 | DataType.UINT64 =>
      val buf = buffer.asLongBuffer()
      val output = LongBuffer.allocate(buf.capacity)
      output.put(buf)
      output.rewind
      output.array()
    case DataType.INT32 | DataType.UINT32 =>
      val buf = buffer.asIntBuffer()
      val output = IntBuffer.allocate(buf.capacity)
      output.put(buf)
      output.rewind
      output.array()
    case DataType.INT16 | DataType.UINT16 =>
      val buf = buffer.asShortBuffer()
      val output = ShortBuffer.allocate(buf.capacity)
      output.put(buf)
      output.rewind
      output.array()
    case DataType.INT8 | DataType.UINT8   =>
      val output = ByteBuffer.allocate(buffer.capacity).order(ByteOrder.nativeOrder)
      output.put(buffer)
      output.rewind
      buffer.array()
    case DataType.BOOL  =>
      DataUtils.convertToBooleanArray(buffer.array())
    case DataType.BYTES =>
      DataUtils.readBinaryString(buffer)
    case DataType.FP16  =>
      Fp16Conversions.convertFp16BufferToFloatBuffer(buffer.asShortBuffer()).array()
    case DataType.BF16  =>
      Fp16Conversions.convertBf16BufferToFloatBuffer(buffer.asShortBuffer()).array()
  }

  def sizeof(datatype: DataType): Int = datatype match {
    case DataType.FP32 | DataType.INT32 | DataType.UINT32 => 4
    case DataType.FP64 | DataType.INT64 | DataType.UINT64 => 8
    case DataType.INT16 | DataType.UINT16 | DataType.FP16 | DataType.BF16 => 2
    case DataType.INT8 | DataType.UINT8 | DataType.BOOL | DataType.BYTES  => 1
  }
}
