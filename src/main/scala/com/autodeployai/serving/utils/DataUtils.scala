/*
 * Copyright (c) 2019-2024 AutoDeployAI
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

}
