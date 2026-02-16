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

package com.autodeployai.serving.model

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.Timestamp
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.autodeployai.serving.errors.{InvalidInputDataException, InvalidInputException}
import com.autodeployai.serving.utils.{Constants, DataUtils, JsonUtils, Utils}
import spray.json._

import java.nio.ByteBuffer
import scala.collection.compat.immutable.ArraySeq

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object TimestampFormat extends RootJsonFormat[Timestamp] {
    private val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    def read(json: JsValue): Timestamp = json match {
      case JsString(s) => new Timestamp(dateFormat.parse(s).getTime)
      case _           => throw DeserializationException("Date expected")
    }

    def write(obj: Timestamp): JsString = JsString(dateFormat.format(obj.getTime))
  }

  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def read(value: JsValue): Any = JsonUtils.jsonToAny(value)
    def write(a: Any): JsValue = JsonUtils.anyToJson(a)
  }

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

  implicit object MapStringToAnyJsonFormat extends JsonFormat[Map[String, Any]] {
    def read(json: JsValue): Map[String, Any] = json match {
      case JsObject(fields) => fields.map[String, Any](x => x._1 -> JsonUtils.jsonToAny(x._2))
      case _                => throw DeserializationException("Object expected")
    }

    def write(map: Map[String, Any]): JsObject = JsonUtils.mapToJson(map)
  }

  implicit object ModelVersionJsonFormat extends JsonFormat[ModelVersion] {
    def read(value: JsValue): ModelVersion = value match {
      case JsNumber(n) => new ModelVersion(n.toInt)
      case JsString(s) => ModelVersion(s)
      case _           => ModelVersion(value.toString)
    }

    def write(a: ModelVersion): JsValue = JsString(a.version)
  }

  implicit val fieldFormat: RootJsonFormat[Field] = jsonFormat5(Field)

  implicit val modelInfoFormat: RootJsonFormat[ModelInfo] = jsonFormat19(ModelInfo)

  implicit val modelMetadataFormat: RootJsonFormat[ModelMetadata] = jsonFormat6(ModelMetadata.apply)

  implicit val metadataTensorFormat: RootJsonFormat[MetadataTensor] = jsonFormat3(MetadataTensor)

  implicit val modelMetadataV2Format: RootJsonFormat[ModelMetadataV2] = jsonFormat5(ModelMetadataV2)

  implicit val deployResponseFormat: RootJsonFormat[DeployResponse] = jsonFormat2(DeployResponse)

  implicit val predictRequestFormat: RootJsonFormat[PredictRequest] = jsonFormat2(PredictRequest)

  implicit val predictResponseFormat: RootJsonFormat[PredictResponse] = jsonFormat1(PredictResponse)

  implicit val serverMetadataResponseFormat: RootJsonFormat[ServerMetadataResponse] = jsonFormat3(ServerMetadataResponse)

  implicit val serverLiveResponseFormat: RootJsonFormat[ServerLiveResponse] = jsonFormat1(ServerLiveResponse)

  implicit val serverReadyResponseFormat: RootJsonFormat[ServerReadyResponse] = jsonFormat1(ServerReadyResponse)

  implicit val modelReadyResponseFormat: RootJsonFormat[ModelReadyResponse] = jsonFormat1(ModelReadyResponse)

  def loadJson[T: JsonReader](filepath: Path): T = {
    new String(Files.readAllBytes(filepath), StandardCharsets.UTF_8).parseJson.convertTo[T]
  }

  def saveJson[T: JsonWriter](filepath: Path, json: T): Path = {
    Files.write(filepath, json.toJson.prettyPrint.getBytes(StandardCharsets.UTF_8))
  }

  implicit object RequestInputJsonFormat extends JsonFormat[RequestInput] {
    def read(value: JsValue): RequestInput = value match {
      case JsObject(fields) => {
        val name = fields("name").convertTo[String]
        val datatype = fields("datatype").convertTo[String]
        val shape = fields("shape").convertTo[Array[Long]]
        val length = Utils.elementCount(shape).toInt
        val data = fields("data") match {
          case array: JsArray  =>
            val (buffer, count) = DataType.withName(datatype) match {
              case DataType.FP32  =>
                val buffer = new Array[Float](length)
                (buffer, JsonUtils.jsArrayToFP32(array, buffer, 0))
              case DataType.FP64 =>
                val buffer = new Array[Double](length)
                (buffer, JsonUtils.jsArrayToFP64(array, buffer, 0))
              case DataType.INT64 | DataType.UINT64 =>
                val buffer = new Array[Long](length)
                (buffer, JsonUtils.jsArrayToINT64(array, buffer, 0))
              case DataType.INT32 | DataType.UINT32 =>
                val buffer = new Array[Int](length)
                (buffer, JsonUtils.jsArrayToINT32(array, buffer, 0))
              case DataType.INT16 | DataType.UINT16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToINT16(array, buffer, 0))
              case DataType.INT8 =>
                val buffer = new Array[Byte](length)
                (buffer, JsonUtils.jsArrayToINT8(array, buffer, 0))
              case DataType.BOOL =>
                val buffer = new Array[Boolean](length)
                (buffer, JsonUtils.jsArrayToBOOL(array, buffer, 0))
              case DataType.BYTES =>
                val buffer = new Array[String](length)
                (buffer, JsonUtils.jsArrayToString(array, buffer, 0))
              case DataType.FP16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToFP16(array, buffer, 0))
              case DataType.BF16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToBF16(array, buffer, 0))
            }

            if (count != length) {
              throw InvalidInputException(name, ArraySeq.unsafeWrapArray(shape), count)
            }
            buffer
          case _ => InvalidInputDataException(name)
        }

        RequestInput(
          name = name,
          shape = ArraySeq.unsafeWrapArray(shape),
          datatype = datatype,
          parameters = fields.get("parameters").map(_.convertTo[Map[String, Any]]),
          data = data)
      }
      case _ => deserializationError("Object expected in field 'inputs' of InferenceRequest")
    }

    def write(a: RequestInput): JsValue = {
      val data = a.data match {
        case array: Array[_] =>
          tensorToJson(array, a.datatype)
        case _ =>
          JsonUtils.anyToJson(a)
      }

      val members: Seq[(String, JsValue)] = Seq("name" -> JsString(a.name),
        "shape" -> a.shape.toJson,
        "datatype" -> JsString(a.datatype),
        "data" -> data) ++ a.parameters.map(x => "parameters" -> x.toJson).toSeq
      JsObject(members:_*)
    }
  }

  implicit object ResponseOutputJsonFormat extends JsonFormat[ResponseOutput] {
    def read(value: JsValue): ResponseOutput = value match {
      case JsObject(fields) =>
        val name = fields("name").convertTo[String]
        val datatype = fields("datatype").convertTo[String]
        val shape = fields("shape").convertTo[Seq[Long]]
        val length = Utils.elementCount(shape).toInt
        val data = fields("data") match {
          case array: JsArray  =>
            val (buffer, count) = DataType.withName(datatype) match {
              case DataType.FP32  =>
                val buffer = new Array[Float](length)
                (buffer, JsonUtils.jsArrayToFP32(array, buffer, 0))
              case DataType.FP64 =>
                val buffer = new Array[Double](length)
                (buffer, JsonUtils.jsArrayToFP64(array, buffer, 0))
              case DataType.INT64 | DataType.UINT64 =>
                val buffer = new Array[Long](length)
                (buffer, JsonUtils.jsArrayToINT64(array, buffer, 0))
              case DataType.INT32 | DataType.UINT32 =>
                val buffer = new Array[Int](length)
                (buffer, JsonUtils.jsArrayToINT32(array, buffer, 0))
              case DataType.INT16 | DataType.UINT16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToINT16(array, buffer, 0))
              case DataType.INT8 | DataType.UINT8 =>
                val buffer = new Array[Byte](length)
                (buffer, JsonUtils.jsArrayToINT8(array, buffer, 0))
              case DataType.BOOL =>
                val buffer = new Array[Boolean](length)
                (buffer, JsonUtils.jsArrayToBOOL(array, buffer, 0))
              case DataType.BYTES =>
                val buffer = new Array[String](length)
                (buffer, JsonUtils.jsArrayToString(array, buffer, 0))
              case DataType.FP16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToFP16(array, buffer, 0))
              case DataType.BF16 =>
                val buffer = new Array[Short](length)
                (buffer, JsonUtils.jsArrayToBF16(array, buffer, 0))
            }

            if (count != length) {
              throw InvalidInputException(name, shape, count)
            }
            buffer
          case _ => deserializationError("Array expected in field 'data' of ResponseOutput")
        }
        ResponseOutput(
          name = name,
          shape = shape,
          datatype = datatype,
          parameters = fields.get("parameters").map(_.convertTo[Map[String, Any]]),
          data = data)
      case _ => deserializationError("Object expected in field 'outputs' of InferenceResponse")
    }

    def write(a: ResponseOutput): JsValue = {
      val data = a.data match {
        case array: Array[_]  =>
          tensorToJson(array, a.datatype)
        case buffer: ByteBuffer =>
          buffer.flip()
          val array = DataUtils.convertToArray(buffer, DataType.withName(a.datatype))
          tensorToJson(array, a.datatype)
        case _ =>
          JsonUtils.anyToJson(a)
      }

      val members: Seq[(String, JsValue)] = Seq(
        "name" -> JsString(a.name),
        "shape" -> a.shape.toJson,
        "datatype" -> JsString(a.datatype),
        "data" -> data
      ) ++ a.parameters.map(x => "parameters" -> x.toJson).toSeq
      JsObject(members: _*)
    }
  }

  implicit val requestOutputFormat: RootJsonFormat[RequestOutput] = jsonFormat2(RequestOutput)

  implicit val inferenceRequestFormat: RootJsonFormat[InferenceRequest] = jsonFormat4(InferenceRequest)

  implicit val inferenceResponseFormat: RootJsonFormat[InferenceResponse] = jsonFormat5(InferenceResponse)

  private def tensorToJson(s: Array[_], datatype: String): JsValue = s match {
    case array: Array[Byte]   =>
      array.toJson
    case array: Array[Float]  =>
      array.toJson
    case array: Array[Double] =>
      array.toJson
    case array: Array[Long]   =>
      array.toJson
    case array: Array[Int]    =>
      array.toJson
    case array: Array[Short]  =>
      array.toJson
    case array: Array[Boolean]  =>
      array.toJson
    case array: Array[String]   =>
      array.toJson
    case array: Array[Array[_]] =>
      val builder = Vector.newBuilder[JsValue]
      builder.sizeHint(array.length)
      var i = 0
      while (i < array.length) {
        builder += tensorToJson(array(i), datatype)
        i += 1
      }
      JsArray(builder.result())
  }

  implicit object DeployConfigJsonFormat extends JsonFormat[DeployConfig] {
    override def read(json: JsValue): DeployConfig = json match {
      case JsObject(fields) =>
        DeployConfig(
          requestTimeoutMs=fields.get(Constants.CONFIG_REQUEST_TIMEOUT_MS).map(_.convertTo[Long]),
          maxBatchSize=fields.get(Constants.CONFIG_MAX_BATCH_SIZE).map(_.convertTo[Int]),
          maxBatchDelayMs=fields.get(Constants.CONFIG_MAX_BATCH_DELAY_MS).map(_.convertTo[Long]),
          warmupCount=fields.get(Constants.CONFIG_WARMUP_COUNT).map(_.convertTo[Int]),
          warmupDataType=fields.get(Constants.CONFIG_WARMUP_DATA_TYPE).map(_.convertTo[String])
        )
      case _                =>
        throw DeserializationException("Object expected")
    }

    override def write(obj: DeployConfig): JsValue = {
      val members = Seq(
        Constants.CONFIG_REQUEST_TIMEOUT_MS -> obj.requestTimeoutMs.map(_.toJson),
        Constants.CONFIG_MAX_BATCH_SIZE -> obj.maxBatchSize.map(_.toJson),
        Constants.CONFIG_MAX_BATCH_DELAY_MS -> obj.maxBatchDelayMs.map(_.toJson),
        Constants.CONFIG_WARMUP_COUNT -> obj.warmupCount.map(_.toJson),
        Constants.CONFIG_WARMUP_DATA_TYPE -> obj.warmupDataType.map(_.toJson)
      ).filter(x => x._2.isDefined).map(x=> x._1 -> x._2.get)
      JsObject(members: _*)
    }
  }
}