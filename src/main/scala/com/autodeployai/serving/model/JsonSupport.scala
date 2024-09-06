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

package com.autodeployai.serving.model

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.Timestamp
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.autodeployai.serving.utils.JsonUtils
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object TimestampFormat extends RootJsonFormat[Timestamp] {
    val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    def read(json: JsValue) = json match {
      case JsString(s) => new Timestamp(dateFormat.parse(s).getTime)
      case _           => throw DeserializationException("Date expected")
    }

    def write(obj: Timestamp) = JsString(dateFormat.format(obj.getTime))
  }

  implicit val fieldFormat = jsonFormat5(Field)

  implicit val modelInfoFormat = jsonFormat19(ModelInfo)

  implicit val modelMetadataFormat = jsonFormat6(ModelMetadata.apply)

  implicit val deployResponseFormat = jsonFormat2(DeployResponse)

  implicit val predictRequestFormat = jsonFormat2(PredictRequest)

  implicit val predictResponseFormat = jsonFormat1(PredictResponse)

  implicit object RecordSpecFormat extends RootJsonFormat[RecordSpec] {
    override def read(json: JsValue): RecordSpec = json match {
      case JsArray(value)  => RecordSpec(Some(value.map(_.convertTo[Map[String, Any]]).toList))
      case JsObject(value) => RecordSpec(columns = value.get("columns").map(_.convertTo[List[String]]),
        data = value.get("data").map(_.convertTo[List[List[Any]]]))
      case _               => throw DeserializationException("RecordsSpec expected")
    }

    override def write(obj: RecordSpec): JsValue = if (obj.records.isDefined) {
      JsArray(obj.records.get.map(x => x.toJson).toVector)
    } else {
      JsObject("columns" -> obj.columns.toJson, "data" -> obj.data.toJson)
    }
  }

  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def read(value: JsValue) = JsonUtils.jsonToAny(value)

    def write(a: Any) = JsonUtils.anyToJson(a)
  }

  implicit object MapStringToAnyJsonFormat extends JsonFormat[Map[String, Any]] {
    def read(json: JsValue) = json match {
      case JsObject(fields) => fields.map[String, Any](x => x._1 -> JsonUtils.jsonToAny(x._2))
      case _                => throw DeserializationException("Object expected")
    }

    def write(map: Map[String, Any]) = JsonUtils.mapToJson(map)
  }

  def loadJson[T: JsonReader](filepath: Path): T = {
    new String(Files.readAllBytes(filepath), StandardCharsets.UTF_8).parseJson.convertTo[T]
  }

  def saveJson[T: JsonWriter](filepath: Path, json: T) = {
    Files.write(filepath, json.toJson.prettyPrint.getBytes(StandardCharsets.UTF_8))
  }
}