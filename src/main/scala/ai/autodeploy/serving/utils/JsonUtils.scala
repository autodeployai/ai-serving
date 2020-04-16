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

package ai.autodeploy.serving.utils

import spray.json.{JsArray, JsFalse, JsNull, JsNumber, JsObject, JsString, JsTrue, JsValue}

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
    case l: java.util.List[_]   => seqToJson(l.asScala.toSeq)
    case m: Map[_, _]           => mapToJson(m)
    case m: java.util.Map[_, _] => mapToJson(m.asScala.toMap)
    case a: Array[_]            => seqToJson(a.toSeq)
    case _                      => JsNull
  }

  def mapToJson(map: Map[_, _]): JsObject = {
    JsObject(map.map[String, JsValue](x => x._1.toString -> anyToJson(x._2)))
  }

  def seqToJson(s: Seq[_]): JsArray = {
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

}
