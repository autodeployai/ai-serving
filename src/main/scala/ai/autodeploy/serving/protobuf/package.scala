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

import java.nio.{ByteBuffer, ByteOrder}

import ai.autodeploy.serving.errors._
import ai.autodeploy.serving.protobuf.Value.Kind
import ai.autodeploy.serving.utils.Utils
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp.Timestamp
import io.grpc.{Status, StatusRuntimeException}
import onnx.TensorProto
import onnx.TensorProto.DataType._

import scala.jdk.CollectionConverters._

package object protobuf {

  implicit class ModelInfoImplicitClass(m: model.ModelInfo) {
    def toPb: ModelInfo = protobuf.toPb(m)
  }

  implicit class DeployResponseImplicitClass(r: model.DeployResponse) {
    def toPb: DeployResponse = protobuf.toPb(r)
  }

  implicit class ModelMetadataImplicitClass(m: model.ModelMetadata) {
    def toPb: ModelMetadata = protobuf.toPb(m)
  }

  implicit class PredictResponseImplicitClass(r: model.PredictResponse) {
    def toPb: PredictResponse = protobuf.toPb(r)
  }

  def toPb(m: model.ModelInfo): ModelInfo = ModelInfo(
    `type` = m.`type`,
    serialization = m.serialization,
    runtime = m.runtime,
    predictors = toPb(m.predictors),
    targets = toPb(m.targets),
    outputs = toPb(m.outputs),
    redundancies = toPb(m.redundancies),
    algorithm = off(m.algorithm),
    functionName = off(m.functionName),
    description = off(m.description),
    version = m.version,
    formatVersion = off(m.formatVersion),
    hash = off(m.hash),
    size = m.size.getOrElse(0),
    createdAt = m.createdAt.map(x => Timestamp(x.getTime)),
    app = off(m.app),
    appVersion = off(m.appVersion),
    copyright = off(m.copyright),
    source = off(m.source)
  )

  def toPb(f: model.Field): Field = Field(
    name = f.name,
    `type` = f.`type`,
    optype = off(f.optype),
    shape = f.shape.getOrElse(Nil),
    values = off(f.values)
  )

  def toPb(seq: Option[Seq[model.Field]]): Seq[Field] =
    seq.map(fields => fields.map(f => toPb(f))).getOrElse(Nil)

  def off(o: Option[String]): String = o.getOrElse("")

  def toPb(ex: Throwable): StatusRuntimeException = ex match {
    case bex: BaseException => {
      val status = bex match {
        case _: ModelNotFoundException          => Status.NOT_FOUND
        case _: OnnxRuntimeLibraryNotFoundError => Status.INTERNAL
        case _                                  => Status.INVALID_ARGUMENT
      }
      new StatusRuntimeException(status.withDescription(bex.message))
    }
    case _                  => {
      new StatusRuntimeException(Status.INTERNAL.withDescription(ex.getMessage))
    }
  }

  def toPb(r: model.DeployResponse): DeployResponse = DeployResponse(
    Some(ModelSpec(r.name, Some(r.version)))
  )

  def toPb(m: model.ModelMetadata): ModelMetadata = ModelMetadata(
    id = m.id,
    name = m.name,
    createdAt = Some(Timestamp(m.createdAt.getTime)),
    updateAt = Some(Timestamp(m.updateAt.getTime)),
    latestVersion = m.latestVersion,
    versions = m.versions.map(versions => versions.map(toPb)).getOrElse(Nil)
  )

  def toPb(r: model.RecordSpec): RecordSpec = RecordSpec(
    records = r.records.map(x => x.map(y => Record(y.map[String, Value](x => x._1 -> anyToValue(x._2))))).getOrElse(Nil),
    columns = r.columns.getOrElse(Nil),
    data = r.data.map(x => x.map(y => ListValue(y.map(anyToValue)))).getOrElse(Nil)
  )

  def toPb(r: model.PredictResponse): PredictResponse = PredictResponse(
    result = Some(toPb(r.result))
  )

  def anyToValue(v: Any): Value = v match {
    case s: String              => Value(Kind.StringValue(s))
    case i: Int                 => Value(Kind.NumberValue(i))
    case i: java.lang.Integer   => Value(Kind.NumberValue(i.doubleValue))
    case l: Long                => Value(Kind.NumberValue(l))
    case l: java.lang.Long      => Value(Kind.NumberValue(l.doubleValue))
    case f: Float               => Value(Kind.NumberValue(f))
    case f: java.lang.Float     => Value(Kind.NumberValue(f.doubleValue))
    case d: Double              => Value(Kind.NumberValue(d))
    case d: java.lang.Double    => Value(Kind.NumberValue(d))
    case s: Short               => Value(Kind.NumberValue(s))
    case s: java.lang.Short     => Value(Kind.NumberValue(s.doubleValue))
    case b: Byte                => Value(Kind.NumberValue(b))
    case b: java.lang.Byte      => Value(Kind.NumberValue(b.doubleValue))
    case n: java.lang.Number    => Value(Kind.NumberValue(n.doubleValue))
    case true                   => Value(Kind.BoolValue(true))
    case false                  => Value(Kind.BoolValue(false))
    case s: Seq[_]              => Value(Kind.ListValue(ListValue(s.map(anyToValue))))
    case l: java.util.List[_]   => Value(Kind.ListValue(ListValue(l.asScala.toSeq.map(anyToValue))))
    case m: Map[_, _]           => Value(Kind.RecordValue(Record(m.map(x => x._1.toString -> anyToValue(x._2)))))
    case m: java.util.Map[_, _] => Value(Kind.RecordValue(Record(m.asScala.toMap.map(x => x._1.toString -> anyToValue(x._2)))))
    // Takes float as default data type
    case b: ByteBuffer => Value(Kind.TensorValue(TensorProto(dataType = FLOAT.index, rawData = ByteString.copyFrom(b))))
    case a: Array[_]   => arrayToValue(a) // Value(Kind.ListValue(ListValue(a.map(anyToValue))))
    case _             => Value(Kind.NullValue(NullValue.NULL_VALUE))
  }

  def arrayToValue(arr: Array[_]): Value = {
    val dims = Utils.shapeOfValue(arr).toSeq
    val flattenArr = Utils.flatten(arr)
    flattenArr match {
      case a: Array[Float]  => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = FLOAT.index,
        floatData = a.toSeq)))
      case a: Array[Double] => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = DOUBLE.index,
        doubleData = a.toSeq)))
      // Takes float as default data type
      case a: Array[Byte]    => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = FLOAT.index,
        rawData = ByteString.copyFrom(a))))
      case a: Array[Short]   => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = INT16.index,
        int32Data = a.toSeq.map(x => x.toInt))))
      case a: Array[Int]     => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = INT32.index,
        int32Data = a.toSeq)))
      case a: Array[Long]    => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = INT64.index,
        int64Data = a.toSeq)))
      case a: Array[Boolean] => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = BOOL.index,
        int32Data = a.toSeq.map(x => if (x) 1 else 0))))
      case a: Array[String]  => Value(Kind.TensorValue(TensorProto(dims = dims, dataType = STRING.index,
        stringData = a.toSeq.map(x => ByteString.copyFromUtf8(x)))))
      case _                 => Value(Kind.ListValue(ListValue(arr.toSeq.map(anyToValue))))
    }
  }


  def fromPb(p: PredictRequest): model.PredictRequest = {
    require(p.x.isDefined, "X is missing in the predict request")

    model.PredictRequest(fromPb(p.x.get), Option(p.filter))
  }

  def fromPb(r: RecordSpec): model.RecordSpec = {
    require(r.records.nonEmpty || (r.columns.nonEmpty && r.data.nonEmpty),
      "either records is present or both columns and data are present together.")

    if (r.records.nonEmpty) {
      model.RecordSpec(records = Some(r.records.map(x => x.fields.map[String, Any](x => x._1 -> fromPb(x._2)))))
    } else {
      model.RecordSpec(columns = Some(r.columns), data = Some(r.data.map(x => x.values.map(fromPb))))
    }
  }

  def fromPb(v: Value): Any = {
    import Value.Kind._
    v.kind match {
      case NumberValue(value) => value
      case StringValue(value) => value
      case BoolValue(value)   => value
      case RecordValue(value) => value.fields.map[String, Any](x => x._1 -> fromPb(x._2))
      case ListValue(value)   => value.values.map(fromPb)
      case TensorValue(value) => fromPb(value)
      case _                  => null
    }
  }

  def fromPb(v: TensorProto): Any = {
    require(v.dataType != UNDEFINED.index, "The element type in the input tensor is not defined.")

    if (!v.rawData.isEmpty) {
      // When this raw_data field is used to store tensor value, elements MUST
      // be stored in as fixed-width, little-endian order.
      v.rawData.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
    } else {
      v.dataType match {
        case FLOAT.index | COMPLEX64.index                                                                    =>
          v.floatData
        case INT32.index | INT16.index | INT8.index | UINT16.index | UINT8.index | BOOL.index | FLOAT16.index =>
          v.int32Data
        case STRING.index                                                                                     =>
          v.stringData
        case INT64.index                                                                                      =>
          v.int64Data
        case DOUBLE.index | COMPLEX128.index                                                                  =>
          v.doubleData
        case UINT32.index | UINT64.index                                                                      =>
          v.uint64Data
        case _                                                                                                =>
          null
      }
    }
  }
}
