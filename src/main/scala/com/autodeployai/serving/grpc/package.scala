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

package com.autodeployai.serving

import java.nio.{ByteBuffer, ByteOrder}
import com.autodeployai.serving.errors.BaseException
import protobuf.Value.Kind
import com.autodeployai.serving.utils.{DataUtils, Utils}
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp.Timestamp
import inference.{InferParameter, ModelInferRequest, ModelInferResponse, ModelMetadataResponse, ServerMetadataResponse}
import protobuf.TensorProto.DataType._

import java.nio.charset.StandardCharsets
import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters._

package object grpc {
  implicit class ModelInfoImplicitClass(m: model.ModelInfo) {
    def toPb: protobuf.ModelInfo = grpc.toPb(m)
  }

  implicit class DeployResponseImplicitClass(r: model.DeployResponse) {
    def toPb: protobuf.DeployResponse = grpc.toPb(r)
  }

  implicit class ModelMetadataImplicitClass(m: model.ModelMetadata) {
    def toPb: protobuf.ModelMetadata = grpc.toPb(m)
  }

  implicit class PredictResponseImplicitClass(r: model.PredictResponse) {
    def toPb: protobuf.PredictResponse = grpc.toPb(r)
  }

  def toPb(m: model.ModelInfo): protobuf.ModelInfo = protobuf.ModelInfo(
    `type` = m.`type`,
    serialization = m.serialization,
    runtime = m.runtime,
    predictors = toPb(m.inputs),
    targets = toPb(m.targets),
    outputs = toPb(m.outputs),
    redundancies = toPb(m.redundancies),
    algorithm = off(m.algorithm),
    functionName = off(m.functionName),
    description = off(m.description),
    version = m.version.map(_.version).getOrElse(""),
    formatVersion = off(m.formatVersion),
    hash = off(m.hash),
    size = m.size.getOrElse(0),
    createdAt = m.createdAt.map(x => Timestamp(x.getTime)),
    app = off(m.app),
    appVersion = off(m.appVersion),
    copyright = off(m.copyright),
    source = off(m.source)
  )

  def toPb(f: model.Field): protobuf.Field = protobuf.Field(
    name = f.name,
    `type` = f.`type`,
    optype = off(f.optype),
    shape = f.shape.getOrElse(Nil),
    values = off(f.values)
  )

  def toPb(seq: Option[Seq[model.Field]]): Seq[protobuf.Field] =
    seq.map(fields => fields.map(f => toPb(f))).getOrElse(Nil)

  def off(o: Option[String]): String = o.getOrElse("")

  def toPb(r: model.DeployResponse): protobuf.DeployResponse = protobuf.DeployResponse(
    Some(protobuf.ModelSpec(r.name, r.version))
  )

  def toPb(m: model.ModelMetadata): protobuf.ModelMetadata = protobuf.ModelMetadata(
    id = m.id,
    name = m.name,
    createdAt = Some(Timestamp(m.createdAt.getTime)),
    updateAt = Some(Timestamp(m.updateAt.getTime)),
    latestVersion = m.latestVersion.version,
    versions = m.versions.map(versions => versions.map(toPb)).getOrElse(Nil)
  )

  def toPb(r: model.RecordSpec): protobuf.RecordSpec = protobuf.RecordSpec(
    records = r.records.map(x => x.map(y => protobuf.Record(y.map[String, protobuf.Value](x => x._1 -> anyToValue(x._2))))).getOrElse(Nil),
    columns = r.columns.getOrElse(Nil),
    data = r.data.map(x => x.map(y => protobuf.ListValue(y.map(anyToValue)))).getOrElse(Nil)
  )

  def toPb(r: model.PredictResponse): protobuf.PredictResponse = protobuf.PredictResponse(
    result = Some(toPb(r.result))
  )

  def toPb(r: model.ServerMetadataResponse): ServerMetadataResponse = ServerMetadataResponse(
    name = r.name,
    version = r.version,
    extensions = r.extensions
  )

  def toPb(r: model.MetadataTensor): ModelMetadataResponse.TensorMetadata = ModelMetadataResponse.TensorMetadata(
    name = r.name,
    datatype = r.datatype,
    shape = r.shape
  )

  def toPb(r: model.ModelMetadataV2): ModelMetadataResponse = ModelMetadataResponse(
    name = r.name,
    versions = r.versions,
    platform = r.platform,
    inputs = r.inputs.map(toPb),
    outputs = r.outputs.map(toPb)
  )

  def toPb(r: model.InferenceResponse): ModelInferResponse =  {
    val (outputs, rawOutputContents: Seq[ByteString]) = if (isRawOutput(r)) {
      val contents: Seq[ByteString]  = r.outputs.map(x => x.data match {
        case buffer: ByteBuffer =>
          buffer.rewind()
          ByteString.copyFrom(buffer)
        case a: Array[Byte]     => ByteString.copyFrom(a);
        case a: Array[String]   => DataUtils.writeBinaryString(a)
        case b => throw new BaseException(s"Unexpected output value: $b")
      })
      (r.outputs.map(x => toPb(x, output_raw = true)), contents)
    } else {
      (r.outputs.map(x => toPb(x, output_raw = false)), Seq.empty)
    }

    ModelInferResponse(
      modelName = r.model_name,
      modelVersion = r.model_version.getOrElse(""),
      id = r.id.getOrElse(""),
      parameters = toPb(r.parameters),
      outputs = outputs,
      rawOutputContents = rawOutputContents
    )
  }

  private def isRawOutput(r: model.InferenceResponse): Boolean = {
    val rawOutput = r.parameters.flatMap(x => x.get("raw_output")).getOrElse(false).asInstanceOf[Boolean]
    if (!rawOutput) r.outputs.exists(x => x.datatype == "FP16" || x.datatype =="BF16" ) else true
  }

  def toPb(r: model.ResponseOutput, output_raw: Boolean): ModelInferResponse.InferOutputTensor = {
    ModelInferResponse.InferOutputTensor(
      name = r.name,
      datatype =  r.datatype,
      shape = r.shape,
      parameters = toPb(r.parameters),
      contents = if (output_raw) None else Option(any2Tensor(r.data, r.datatype))
    )
  }

  private def any2Tensor(r: Any, datatype: String): inference.InferTensorContents = datatype match {
    case "BOOL" =>
      r match {
        case a: Array[Byte] =>
          inference.InferTensorContents(boolContents=ArraySeq.unsafeWrapArray(a.map(x => x != 0)))
      }
    case "UINT8" =>
      r match {
        case a: Array[Byte] =>
          inference.InferTensorContents(uintContents=ArraySeq.unsafeWrapArray(a.map(_.toInt)))
      }
    case "INT8" =>
      r match {
        case a: Array[Byte] =>
          inference.InferTensorContents(intContents=ArraySeq.unsafeWrapArray(a.map(_.toInt)))
      }
    case "INT16" | "UINT16" =>
      r match {
        case a: Array[Short] =>
          inference.InferTensorContents(intContents=ArraySeq.unsafeWrapArray(a.map(_.toInt)))
      }
    case "INT32" | "UINT32" =>
      r match {
        case a: Array[Int] =>
          inference.InferTensorContents(intContents=ArraySeq.unsafeWrapArray(a))
      }
    case "INT64" | "UINT64" =>
      r match {
        case a: Array[Long] =>
          inference.InferTensorContents(int64Contents=ArraySeq.unsafeWrapArray(a))
      }
    case "FP32" =>
      r match {
        case a: Array[Float] =>
          inference.InferTensorContents(fp32Contents=ArraySeq.unsafeWrapArray(a))
      }
    case "FP64" =>
      r match {
        case a: Array[Double] =>
          inference.InferTensorContents(fp64Contents=ArraySeq.unsafeWrapArray(a))
      }
    case "BYTES" =>
      r match {
        case a: Array[String] =>
          inference.InferTensorContents(bytesContents=ArraySeq.unsafeWrapArray(a.map(x => ByteString.copyFrom(x, StandardCharsets.UTF_8))))
      }
    case "FP16" | "BF16"  =>
      throw new BaseException(s"The $datatype data type must be represented as raw content as there is no specific data type for a 16-bit float type.")
  }

  def toPb(parameters: Option[Map[String, Any]]): Map[String, inference.InferParameter] = {
    parameters.map(x => {
      x.map(pair => {
        pair._1 -> InferParameter(pair._2 match {
          case b: Boolean => InferParameter.ParameterChoice.BoolParam(b)
          case l: Long => InferParameter.ParameterChoice.Int64Param(l)
          case i: Int => InferParameter.ParameterChoice.Int64Param(i)
          case _  => InferParameter.ParameterChoice.StringParam(pair._2.toString)
        })
      })
    }).getOrElse(Map.empty)
  }

  private def anyToValue(v: Any): protobuf.Value = v match {
    case s: String              => protobuf.Value(Kind.StringValue(s))
    case i: Int                 => protobuf.Value(Kind.NumberValue(i))
    case i: java.lang.Integer   => protobuf.Value(Kind.NumberValue(i.doubleValue()))
    case l: Long                => protobuf.Value(Kind.NumberValue(l.doubleValue()))
    case l: java.lang.Long      => protobuf.Value(Kind.NumberValue(l.doubleValue()))
    case f: Float               => protobuf.Value(Kind.NumberValue(f))
    case f: java.lang.Float     => protobuf.Value(Kind.NumberValue(f.doubleValue()))
    case d: Double              => protobuf.Value(Kind.NumberValue(d))
    case d: java.lang.Double    => protobuf.Value(Kind.NumberValue(d))
    case s: Short               => protobuf.Value(Kind.NumberValue(s))
    case s: java.lang.Short     => protobuf.Value(Kind.NumberValue(s.doubleValue()))
    case b: Byte                => protobuf.Value(Kind.NumberValue(b))
    case b: java.lang.Byte      => protobuf.Value(Kind.NumberValue(b.doubleValue()))
    case n: java.lang.Number    => protobuf.Value(Kind.NumberValue(n.doubleValue()))
    case true                   => protobuf.Value(Kind.BoolValue(true))
    case false                  => protobuf.Value(Kind.BoolValue(false))
    case s: Seq[_]              => protobuf.Value(Kind.ListValue(protobuf.ListValue(s.map(anyToValue))))
    case l: java.util.List[_]   => protobuf.Value(Kind.ListValue(protobuf.ListValue(l.asScala.toSeq.map(anyToValue))))
    case m: Map[_, _]           => protobuf.Value(Kind.RecordValue(protobuf.Record(m.map(x => x._1.toString -> anyToValue(x._2)))))
    case m: java.util.Map[_, _] => protobuf.Value(Kind.RecordValue(protobuf.Record(m.asScala.toMap.map(x => x._1.toString -> anyToValue(x._2)))))
    // Takes float as default data type
    case b: ByteBuffer => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dataType = FLOAT.index, rawData = ByteString.copyFrom(b))))
    case a: Array[_]   => arrayToValue(a) // Value(Kind.ListValue(ListValue(a.map(anyToValue))))
    case _             => protobuf.Value(Kind.NullValue(protobuf.NullValue.NULL_VALUE))
  }

  def arrayToValue(arr: Array[_]): protobuf.Value = {
    val dims = Utils.shapeOfValue(arr).toSeq
    val flattenArr = Utils.flatten(arr)
    flattenArr match {
      case a: Array[Float]  => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = FLOAT.index,
        floatData = a.toSeq)))
      case a: Array[Double] => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = DOUBLE.index,
        doubleData = a.toSeq)))
      // Takes float as default data type
      case a: Array[Byte]    => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = FLOAT.index,
        rawData = ByteString.copyFrom(a))))
      case a: Array[Short]   => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = INT16.index,
        int32Data = a.toSeq.map(x => x.toInt))))
      case a: Array[Int]     => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = INT32.index,
        int32Data = a.toSeq)))
      case a: Array[Long]    => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = INT64.index,
        int64Data = a.toSeq)))
      case a: Array[Boolean] => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = BOOL.index,
        int32Data = a.toSeq.map(x => if (x) 1 else 0))))
      case a: Array[String]  => protobuf.Value(Kind.TensorValue(protobuf.TensorProto(dims = dims, dataType = STRING.index,
        stringData = a.toSeq.map(x => ByteString.copyFromUtf8(x)))))
      case _                 => protobuf.Value(Kind.ListValue(protobuf.ListValue(arr.toSeq.map(anyToValue))))
    }
  }


  def fromPb(p: protobuf.PredictRequest): model.PredictRequest = {
    require(p.x.isDefined, "X is missing in the predict request")

    model.PredictRequest(fromPb(p.x.get), Option(p.filter))
  }

  def fromPb(c: protobuf.DeployConfig): model.DeployConfig = {
    model.DeployConfig(
      requestTimeoutMs = if (c.requestTimeoutMs > 0L) Some(c.requestTimeoutMs) else None,
      maxBatchSize = if (c.maxBatchSize > 0) Some(c.maxBatchSize) else None,
      maxBatchDelayMs = if (c.maxBatchDelayMs > 0L) Some(c.maxBatchDelayMs) else None,
      warmupCount = if (c.warmupCount > 0) Some(c.warmupCount) else None,
      warmupDataType = if (c.warmupDataType.nonEmpty) Some(c.warmupDataType) else None
    )
  }

  def fromPb(r: protobuf.RecordSpec): model.RecordSpec = {
    require(r.records.nonEmpty || (r.columns.nonEmpty && r.data.nonEmpty),
      "either records is present or both columns and data are present together.")

    if (r.records.nonEmpty) {
      model.RecordSpec(records = Some(r.records.map(x => x.fields.map[String, Any](x => x._1 -> fromPb(x._2)))))
    } else {
      model.RecordSpec(columns = Some(r.columns), data = Some(r.data.map(x => x.values.map(fromPb))))
    }
  }

  def fromPb(v: protobuf.Value): Any = {
    import protobuf.Value.Kind._
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

  def fromPb(v: protobuf.TensorProto): Any = {
    require(v.dataType != UNDEFINED.index, "The element type in the input tensor is not defined.")

    if (!v.rawData.isEmpty) {
      // When this raw_data field is used to store tensor value, elements MUST
      // be stored in as fixed-width, little-endian order.
      (v.rawData.asReadOnlyByteBuffer().order(ByteOrder.nativeOrder), v.dims)
    } else {
      v.dataType match {
        case FLOAT.index | COMPLEX64.index                                                                    =>
          (v.floatData, v.dims)
        case INT32.index | INT16.index | INT8.index | UINT16.index | UINT8.index | BOOL.index | FLOAT16.index =>
          (v.int32Data, v.dims)
        case STRING.index                                                                                     =>
          (v.stringData, v.dims)
        case INT64.index                                                                                      =>
          (v.int64Data, v.dims)
        case DOUBLE.index | COMPLEX128.index                                                                  =>
          (v.doubleData, v.dims)
        case UINT32.index | UINT64.index                                                                      =>
          (v.uint64Data, v.dims)
        case _                                                                                                =>
          null
      }
    }
  }

  def fromPb(request: ModelInferRequest): model.InferenceRequest = {
    val rawInputContents = request.rawInputContents
    val inputs = if (rawInputContents.nonEmpty) {
      if (rawInputContents.size != request.inputs.size) {
        throw new BaseException(s"The length of raw_input_contents should be ${request.inputs.size}, but got ${rawInputContents.size}")
      }

      request.inputs.zip(rawInputContents).map { x =>
        val tensor = x._1
        model.RequestInput(
          name = tensor.name,
          shape = tensor.shape,
          datatype = tensor.datatype,
          parameters = Utils.toOption(tensor.parameters.map(x => x._1 -> fromPb(x._2))),
          data = x._2.asReadOnlyByteBuffer().order(ByteOrder.nativeOrder)
        )
      }
    } else {
      request.inputs.map(fromPb)
    }

    model.InferenceRequest(
      id = Utils.toOption(request.id),
      parameters = Utils.toOption(request.parameters.map(x => x._1 -> fromPb(x._2))),
      inputs = inputs,
      outputs = Utils.toOption(request.outputs.map(fromPb))
    )
  }

  def fromPb(output: ModelInferRequest.InferRequestedOutputTensor): model.RequestOutput = {
    model.RequestOutput(name = output.name, parameters = Utils.toOption(output.parameters.map(x => x._1 -> fromPb(x._2))))
  }

  def fromPb(parameter: inference.InferParameter): Any = {
    val choice = parameter.parameterChoice
    val result = if (choice.isBoolParam)
      choice.boolParam
    else if (choice.isStringParam)
      choice.stringParam
    else if (choice.isInt64Param)
      choice.int64Param
    else None
    result.orNull
  }

  def fromPb(tensor: ModelInferRequest.InferInputTensor): model.RequestInput = {
    model.RequestInput(
      name = tensor.name,
      shape = tensor.shape,
      datatype = tensor.datatype,
      parameters = Utils.toOption(tensor.parameters.map(x => x._1 -> fromPb(x._2))),
      data = tensor.contents.map(fromPb(tensor.datatype, _)).orNull
    )
  }

  def fromPb(datatype: String, content: inference.InferTensorContents): Any = {
    model.DataType.withName(datatype) match {
      case model.DataType.BOOL =>
        content.boolContents.toArray
      case model.DataType.INT32 | model.DataType.INT16 | model.DataType.INT8 =>
        content.intContents.toArray
      case model.DataType.INT64 =>
        content.int64Contents.toArray
      case model.DataType.UINT32 | model.DataType.UINT16 | model.DataType.UINT8 =>
        content.uintContents.toArray
      case model.DataType.UINT64 =>
        content.uint64Contents.toArray
      case model.DataType.FP32 =>
        content.fp32Contents.toArray
      case model.DataType.FP64 =>
        content.fp64Contents.toArray
      case model.DataType.BYTES =>
        if (content.bytesContents.size == 1) {
          content.bytesContents.head.asReadOnlyByteBuffer().order(ByteOrder.nativeOrder)
        } else {
          ByteString.copyFrom(content.bytesContents.asJava).asReadOnlyByteBuffer().order(ByteOrder.nativeOrder)
        }
      case _ =>
        throw new BaseException(s"The $datatype data type must be represented as raw content as there is no specific data type for a 16-bit float type.")
    }
  }
}
