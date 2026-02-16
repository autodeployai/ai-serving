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

package com.autodeployai.serving.deploy

import java.nio._
import java.nio.file.Path
import com.autodeployai.serving.utils.DataUtils._
import com.autodeployai.serving.utils.Utils._
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime._
import com.autodeployai.serving.errors._
import com.autodeployai.serving.model._
import com.autodeployai.serving.utils.{Constants, DataUtils, Utils}
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

import java.util
import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.{Failure, Random, Success, Using}

case class InputTensor(name: String, info: TensorInfo)

case class InputsWrapper(inputs: java.util.Map[String, OnnxTensor]) extends AutoCloseable {
  override def close(): Unit = {
    val it = inputs.values.iterator()
    while (it.hasNext) {
      safeClose(it.next)
    }
  }
}

/**
 * Supports the model of Open Neural Network Exchange (ONNX)
 */
class OnnxModel(val session: OrtSession,
                val env: OrtEnvironment,
                override val modelName: String,
                override val modelVersion: String,
                override val config: Option[Config]
               )(implicit ec: ExecutionContext) extends PredictModel {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  // Create batch processor if it's supported and properly configured
  override val batchProcessorV2: Option[BatchProcessor[InferenceRequest, InferenceResponse]] =
    createBatchProcessorV2(this, config)

  // A list of input tensors
  private val inputTensors: Array[InputTensor] = {
    val inputs = session.getInputInfo
    val result = new Array[InputTensor](inputs.size())

    var i = 0
    val it = inputs.entrySet().iterator()
    while (it.hasNext) {
      val entry = it.next()
      // Suppose inputs are all tensor instances
      entry.getValue.getInfo match {
        case tensorInfo: TensorInfo =>
          result(i) = InputTensor(entry.getKey, tensorInfo)
          i += 1
        case x  => InputNotSupportedException(entry.getKey, x.toString)
      }
    }
    result
  }

  // A list of output tensor with a flag if there is an output tensor type with "BF16" or "FP16"
  private val (outputTensorNames, hasFP16) = {
    val outputs = session.getOutputInfo
    val result = new util.HashSet[String]()
    var fp16 = false

    val it = outputs.entrySet().iterator()
    while (it.hasNext) {
      val entry = it.next()
      entry.getValue.getInfo match {
        case info: TensorInfo =>
          result.add(entry.getKey)

          if (!fp16) {
            fp16 = info.`type` == OnnxJavaType.FLOAT16 || info.`type` == OnnxJavaType.BFLOAT16
          }
        case x =>
          log.warn(s"The non-tensor output ${entry.getKey} with $x ignored")
      }
    }
    (result, fp16)
  }

  private val outputNames: java.util.Set[String] = session.getOutputNames

  // Start the warmup process if it's configured properly.
  warmup()

  override def predict(request: PredictRequest): PredictResponse = {

    val requestedOutput = request.filter.flatMap(x => toOption(x)).map(x => {
      val set = new util.HashSet[String]()
      x.foreach(output => {
        if (outputNames.contains(output)) {
          set.add(output)
        }
      })
      set
    }).getOrElse(outputNames)

    val result: RecordSpec = if (request.X.records.isDefined) {
      RecordSpec(records = request.X.records.map(records => {
        records.map(record => {
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))

            val outputs = Map.newBuilder[String, Any]
            outputs.sizeHint(result.size())
            val it = result.iterator()
            while (it.hasNext) {
              val entry = it.next()
              outputs += entry.getKey -> entry.getValue.getValue
            }
            outputs.result()
          } match {
            case Success(value) => value
            case Failure(ex)    => throw ex
          }
        })
      }))
    } else {
      val columns = request.X.columns.get
      var outputColumns: Seq[String] = null
      val outputColumnsBuilder = Seq.newBuilder[String]
      outputColumnsBuilder.sizeHint(requestedOutput.size())
      val outputData = request.X.data.map(data => {
        data.map(values => {
          val record = columns.zip(values).toMap
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))

            val outputs = Seq.newBuilder[Any]
            outputs.sizeHint(result.size())
            val it = result.iterator()
            while (it.hasNext) {
              val entry = it.next()
              outputs += entry.getValue.getValue

              if (outputColumns == null) {
                outputColumnsBuilder += entry.getKey
              }
            }

            if (outputColumns == null) {
              outputColumns = outputColumnsBuilder.result()
            }
            outputs.result()
          } match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
        })
      })
      RecordSpec(columns = Some(outputColumns), data = outputData)
    }
    PredictResponse(result)
  }

  override def predict(request: InferenceRequest): InferenceResponse = {
    val requestedOutput = request.outputs.map(x => {
      val set = new util.HashSet[String]()
      x.foreach(output => {
        if (outputTensorNames.contains(output.name)) {
          set.add(output.name)
        }
      })
      set
    }).getOrElse(outputTensorNames)
    val inputs = request.inputs.map(x => x.name -> x).toMap

    val rawOutput = {
      val flag = request.parameters.flatMap(x => x.get("raw_output")).getOrElse(false).asInstanceOf[Boolean]
      if (!flag) hasFP16 else true
    }

    Using.Manager { use =>
      val wrapper = use(createInputsWrapperV2(inputs))
      val result = use(session.run(wrapper.inputs, requestedOutput))

      val outputs = Seq.newBuilder[ResponseOutput]
      outputs.sizeHint(result.size())
      val it = result.iterator()
      while (it.hasNext) {
        val entry = it.next()
        val onnxValue: OnnxValue = entry.getValue
        onnxValue match {
          case tensor: OnnxTensor =>
            val info = tensor.getInfo
            val data = if (info.`type` == OnnxJavaType.STRING) {
              OrtUtil.flattenString(tensor.getValue)
            } else {
              if (rawOutput) {
                tensor.getByteBuffer
              } else {
                info.`type` match {
                  case OnnxJavaType.FLOAT | OnnxJavaType.FLOAT16 | OnnxJavaType.BFLOAT16  =>
                    tensor.getFloatBuffer.array()
                  case OnnxJavaType.DOUBLE  =>
                    tensor.getDoubleBuffer.array()
                  case OnnxJavaType.INT32   =>
                    tensor.getIntBuffer.array()
                  case OnnxJavaType.INT64   =>
                    tensor.getLongBuffer.array()
                  case OnnxJavaType.INT8 | OnnxJavaType.UINT8 =>
                    tensor.getByteBuffer.array()
                  case OnnxJavaType.BOOL    =>
                    tensor.getByteBuffer.array().map(x => x != 0)
                  case OnnxJavaType.INT16   => tensor.getShortBuffer.array()
                  case _ => OutputNotSupportedException(entry.getKey, info.`type`.toString)
                }
              }
            }
            outputs += ResponseOutput(name=entry.getKey,
              shape=ArraySeq.unsafeWrapArray(info.getShape),
              datatype=mapOnnxJavaType(info.`type`),
              parameters=None,
              data=data
            )
          case _ =>
        }
      }
      outputs.result()
    } match {
      case Success(value) => InferenceResponse(
        model_name=modelName,
        model_version=Option(modelVersion),
        id=request.id,
        parameters=request.parameters,
        outputs=value
      )
      case Failure(ex)    => throw ex
    }
  }

  override def `type`(): String = "ONNX"

  override def runtime(): String = "onnxruntime"

  override def serialization(): String = "onnx"

  override def inputs(): Seq[Field] = {
    toFields(session.getInputInfo.values())
  }

  override def outputs(): Seq[Field] = {
    toFields(session.getOutputInfo.values())
  }

  override def close(): Unit = {
    batchProcessorV2.foreach(_.close())
    safeClose(session)
  }

  private def createInputsWrapper(record: Map[String, Any]): InputsWrapper = {
    val len = inputTensors.length
    val inputs = new java.util.HashMap[String, OnnxTensor](len)
    var i = 0
    while (i < len) {
      val tensor = inputTensors(i)
      val name = tensor.name
      inputs.put(name, convertToTensor(name, tensor.info, record.get(name)))
      i += 1
    }
    InputsWrapper(inputs)
  }

  private def createInputsWrapperV2(record: Map[String, RequestInput]): InputsWrapper = {
    val len = inputTensors.length
    val inputs = new java.util.HashMap[String, OnnxTensor](len)
    var i = 0
    while (i < len) {
      val tensor = inputTensors(i)
      val name = tensor.name
      val input = record.get(name)
      inputs.put(name, convertToTensor(name, tensor.info, input.map(_.data), input.map(_.shape)))
      i += 1
    }
    InputsWrapper(inputs)
  }

  private def toFields(nodes: java.util.Collection[NodeInfo]): Seq[Field] = {
    val builder = Seq.newBuilder[Field]
    val it = nodes.iterator()
    while (it.hasNext) {
      builder += toField(it.next())
    }
    builder.result()
  }

  private def toField(node: NodeInfo): Field = {
    val name = node.getName
    node.getInfo match {
      case x: TensorInfo   =>
        Field(name, s"tensor[${mapOnnxJavaType(x.`type`)}]", shape = if (x.isScalar) None else Some(ArraySeq.unsafeWrapArray(x.getShape)))
      case x: MapInfo      =>
        Field(name, s"map[${mapOnnxJavaType(x.keyType)}:${mapOnnxJavaType(x.valueType)}]", Some(s"${mapOnnxJavaType(x.keyType)}:${mapOnnxJavaType(x.valueType)}"), shape = Some(Seq(x.size.toLong)))
      case x: SequenceInfo => if (x.isSequenceOfMaps) {
        Field(name, s"sequence[map[${mapOnnxJavaType(x.mapInfo.keyType)}:${mapOnnxJavaType(x.mapInfo.valueType)}]]", shape = Some(Seq(x.length.toLong)))
      } else {
        Field(name, s"sequence[${mapOnnxJavaType(x.sequenceType)}]", shape = Some(Seq(x.length.toLong)))
      }
    }
  }

  @tailrec
  private def convertToTensor(name: String, tensorInfo: TensorInfo, inputValue: Option[Any], inputShape: Option[Seq[_]] = None): OnnxTensor = inputValue match {
    case Some(value) =>
      import OnnxJavaType._
      val expectedShape = tensorInfo.getShape
      value match {
        case (v, s: Seq[_]) =>
          convertToTensor(name, tensorInfo, Option(v), Option(s))
        case buffer: ByteBuffer =>
          val shape = inputShape.map(x => convertShape(x)).getOrElse(expectedShape)
          val convertedShape = if (isDynamicShape(expectedShape)) shape else expectedShape
          tensorInfo.`type` match {
            case FLOAT   =>
              OnnxTensor.createTensor(env, buffer.asFloatBuffer(), convertedShape)
            case DOUBLE  =>
              OnnxTensor.createTensor(env, buffer.asDoubleBuffer(), convertedShape)
            case INT8    =>
              OnnxTensor.createTensor(env, buffer, convertedShape)
            case INT16   =>
              OnnxTensor.createTensor(env, buffer.asShortBuffer(), convertedShape)
            case INT32   =>
              OnnxTensor.createTensor(env, buffer.asIntBuffer(), convertedShape)
            case INT64   =>
              OnnxTensor.createTensor(env, buffer.asLongBuffer(), convertedShape)
            case BOOL    =>
              OnnxTensor.createTensor(env, buffer, convertedShape, OnnxJavaType.BOOL)
            case UINT8   =>
              OnnxTensor.createTensor(env, buffer, convertedShape, OnnxJavaType.UINT8)
            case FLOAT16  =>
              OnnxTensor.createTensor(env, buffer.asShortBuffer(), convertedShape, OnnxJavaType.FLOAT16)
            case BFLOAT16 =>
              OnnxTensor.createTensor(env, buffer.asShortBuffer(), convertedShape, OnnxJavaType.BFLOAT16)
            case STRING   =>
              OnnxTensor.createTensor(env, DataUtils.readBinaryString(buffer), shape)
            case UNKNOWN =>
              throw UnknownDataTypeException(name)
          }
        case _                  =>
          val shape = inputShape.map(x => convertShape(x)).getOrElse(shapeOfValue(value))
          val count = elementCount(shape)

          // The expected shape could contain dynamic axes that take -1
          val expectedCount = elementCount(expectedShape)
          if (count % expectedCount != 0) {
            throw ShapeMismatchException(shape, expectedShape)
          }

          val convertedShape = if (isDynamicShape(expectedShape)) shape else expectedShape
          val intCount = count.toInt
          tensorInfo.`type` match {
            case FLOAT   =>
              val data = copyToBuffer[Float](intCount, value)
              OnnxTensor.createTensor(env, FloatBuffer.wrap(data), convertedShape)
            case DOUBLE  =>
              val data = copyToBuffer[Double](intCount, value)
              OnnxTensor.createTensor(env, DoubleBuffer.wrap(data), convertedShape)
            case INT8    =>
              val data = copyToBuffer[Byte](intCount, value)
              OnnxTensor.createTensor(env, ByteBuffer.wrap(data), convertedShape)
            case INT16   =>
              val data = copyToBuffer[Short](intCount, value)
              OnnxTensor.createTensor(env, ShortBuffer.wrap(data), convertedShape)
            case INT32   =>
              val data = copyToBuffer[Int](intCount, value)
              OnnxTensor.createTensor(env, IntBuffer.wrap(data), convertedShape)
            case INT64   =>
              val data = copyToBuffer[Long](intCount, value)
              OnnxTensor.createTensor(env, LongBuffer.wrap(data), convertedShape)
            case BOOL    =>
              val data = copyToBuffer[Boolean](intCount, value)
              OnnxTensor.createTensor(env, OrtUtil.reshape(data, convertedShape))
            case STRING  =>
              val data = copyToBuffer[String](intCount, value)
              OnnxTensor.createTensor(env, data, convertedShape)
            case UINT8   =>
              val data = copyToBuffer[Byte](intCount, value)
              OnnxTensor.createTensor(env, ByteBuffer.wrap(data), convertedShape, OnnxJavaType.UINT8)
            case FLOAT16  =>
              val data = copyToBuffer[Short](intCount, value)
              OnnxTensor.createTensor(env, ShortBuffer.wrap(data), convertedShape, OnnxJavaType.FLOAT16)
            case BFLOAT16  =>
              val data = copyToBuffer[Short](intCount, value)
              OnnxTensor.createTensor(env, ShortBuffer.wrap(data), convertedShape, OnnxJavaType.BFLOAT16)
            case UNKNOWN =>
              throw UnknownDataTypeException(name)
          }
      }
    case _           => throw MissingValueException(name)
  }

  private def copyToBuffer[@specialized(Float, Double, Byte, Short, Int, Long, Boolean) T: ClassTag](len: Int, value: Any): Array[T] = {
    val tag = implicitly[ClassTag[T]]
    val result = tag.runtimeClass match {
      case java.lang.Float.TYPE   =>
        value match {
          case array: Array[Float] => array
          case _ =>
            val data = Array.ofDim[Float](len)
            copy[Float](data, 0, value, anyToFloat)
            data
        }
      case java.lang.Double.TYPE  =>
        value match {
          case array: Array[Double] => array
          case _ =>
            val data = Array.ofDim[Double](len)
            copy[Double](data, 0, value, anyToDouble)
            data
        }
      case java.lang.Integer.TYPE =>
        value match {
          case array: Array[Int] => array
          case _ =>
            val data = Array.ofDim[Int](len)
            copy[Int](data, 0, value, anyToInt)
            data
        }
      case java.lang.Long.TYPE =>
        value match {
          case array: Array[Long] => array
          case _ =>
            val data = Array.ofDim[Long](len)
            copy[Long](data, 0, value, anyToLong)
            data
        }
      case java.lang.Byte.TYPE =>
        value match {
          case array: Array[Byte] => array
          case _ =>
            val data = Array.ofDim[Byte](len)
            copy[Byte](data, 0, value, anyToByte)
            data
        }
      case java.lang.Short.TYPE =>
        value match {
          case array: Array[Short] => array
          case _ =>
            val data = Array.ofDim[Short](len)
            copy[Short](data, 0, value, anyToShort)
            data
        }
      case java.lang.Boolean.TYPE =>
        value match {
          case array: Array[Boolean] => array
          case _ =>
            val data = Array.ofDim[Boolean](len)
            copy[Boolean](data, 0, value, anyToBoolean)
            data
        }
      case _ =>
        value match {
          case array: Array[String] => array
          case _ =>
            val data = Array.ofDim[String](len)
            copy[String](data, 0, value, anyToString)
            data
        }
    }
    result.asInstanceOf[Array[T]]
  }

  private def copy[T](data: Array[T], pos: Int, value: Any, converter: Any => T): Int = value match {
    case seq: Seq[_] =>
      var idx = pos
      seq.foreach {
        x =>
          idx = copy(data, idx, x, converter)
      }
      idx
    case _           =>
      data.update(pos, converter(value))
      pos + 1
  }

  private def convertShape(shape: Seq[_]): Array[Long] = {
    val result = Array.ofDim[Long](shape.length)
    shape.zipWithIndex.foreach(x =>
      result(x._2) = x._1 match {
        case element: Long    => element
        case element: Number  => element.longValue()
        case _                => x.toString().toLong
      }
    )
    result
  }

  private def mapOnnxJavaType(`type`: OnnxJavaType): String = `type` match {
    case OnnxJavaType.FLOAT   => "FP32"
    case OnnxJavaType.DOUBLE  => "FP64"
    case OnnxJavaType.INT8    => "INT8"
    case OnnxJavaType.UINT8   => "UINT8"
    case OnnxJavaType.INT16   => "INT16"
    case OnnxJavaType.INT32   => "INT32"
    case OnnxJavaType.INT64   => "INT64"
    case OnnxJavaType.BOOL    => "BOOL"
    case OnnxJavaType.STRING  => "BYTES"
    case OnnxJavaType.FLOAT16 => "FP16"
    case OnnxJavaType.BFLOAT16=> "BF16"
    case _                    => "UNKNOWN"
  }

  override def warmup(): Unit = {
    val (warmupCount, warmupDataType) = warmupConfig()
    if (warmupCount > 0) {
      log.info(s"Warmup for the model $modelName:$modelVersion initialized: warmup-count=$warmupCount, warmup-data-type=$warmupDataType")
      val start = System.currentTimeMillis()
      var i = 0
      var nSuccess = 0
      warmupDataType match {
        case Constants.CONFIG_WARMUP_DATA_TYPE_ZERO =>
          try {
            val carrier = makeCarrier()
            while (i < warmupCount) {
              val zeroRecord = getZeroData(carrier)
              Using.Manager { use =>
                val wrapper = use(zeroRecord)
                use(session.run(wrapper.inputs, outputNames))
                nSuccess += 1
              }
              i += 1
            }
          } catch {
            case ex: Exception => log.warn("Warmup failed for the model ${modelName}:${modelVersion} when making prediction against the zero data", ex)
          }
        case _ =>
          val carrier = makeCarrier()
          while (i < warmupCount) {
            try {
              val randomRecord = getRandomData(carrier)
              Using.Manager { use =>
                val wrapper = use(randomRecord)
                use(session.run(wrapper.inputs, outputNames))
                nSuccess += 1
              }
            } catch {
              case ex: Exception =>
                log.warn("Warmup failed for the model ${modelName}:${modelVersion} when making prediction against a random data", ex)
            }
            i += 1
          }
      }

      log.info(s"Warmup for the model $modelName:$modelVersion finished in ${System.currentTimeMillis() - start} milliseconds: $i submitted, $nSuccess succeeded")
    }
  }

  private def makeCarrier(): java.util.HashMap[String, Any] = {
    val len = inputTensors.length
    val result = new java.util.HashMap[String, Any](len)
    var i = 0
    while (i < len) {
      val tensor = inputTensors(i)
      val name = tensor.name
      val tensorInfo = tensor.info
      val len = Utils.elementCount(tensorInfo.getShape).toInt
      val input = tensorInfo.`type` match {
        case OnnxJavaType.FLOAT | OnnxJavaType.BFLOAT16 | OnnxJavaType.FLOAT16 =>
          new Array[Float](len)
        case OnnxJavaType.DOUBLE =>
          new Array[Double](len)
        case OnnxJavaType.UINT8 | OnnxJavaType.INT8=>
          new Array[Byte](len)
        case OnnxJavaType.INT32 =>
          new Array[Int](len)
        case OnnxJavaType.INT64 =>
          new Array[Long](len)
        case OnnxJavaType.INT16 =>
          new Array[Short](len)
        case OnnxJavaType.BOOL  =>
          new Array[Boolean](len)
        case OnnxJavaType.STRING  =>
          Array.fill(len)("")
      }
      result.put(name, input)
      i += 1
    }
    result
  }

  private def getZeroData(carrier: java.util.HashMap[String, Any]): InputsWrapper = {
    val len = inputTensors.length
    val inputs = new java.util.HashMap[String, OnnxTensor](len)
    var i = 0
    while (i < len) {
      val tensor = inputTensors(i)
      val name = tensor.name
      val tensorInfo = tensor.info
      val input = carrier.get(name)
      inputs.put(name, convertToTensor(name, tensor.info, Some(input), Some(tensorInfo.getShape.toSeq)))
      i += 1
    }
    InputsWrapper(inputs)
  }

  private def getRandomData(carrier: java.util.HashMap[String, Any]): InputsWrapper = {
    val random = new Random
    val len = inputTensors.length
    val inputs = new java.util.HashMap[String, OnnxTensor](len)
    var i = 0
    while (i < len) {
      val tensor = inputTensors(i)
      val name = tensor.name
      val tensorInfo = tensor.info
      val input = carrier.get(name)
      var j = 0
      input match {
        case arr: Array[Float] =>
          while (j < arr.length) {
            arr(j) = random.nextFloat()
            j += 1
          }
        case arr: Array[Double] =>
          while (j < arr.length) {
            arr(j) = random.nextDouble()
            j += 1
          }
        case arr: Array[Byte] =>
          while (j < arr.length) {
            arr(j) = random.nextInt().toByte
            j += 1
          }
        case arr: Array[Short] =>
          while (j < arr.length) {
            arr(j) = random.nextInt().toShort
            j += 1
          }
        case arr: Array[Int] =>
          while (j < arr.length) {
            arr(j) = random.nextInt()
            j += 1
          }
        case arr: Array[Long] =>
          while (j < arr.length) {
            arr(j) = random.nextLong()
            j += 1
          }
        case arr: Array[Boolean] =>
          while (j < arr.length) {
            arr(j) = random.nextBoolean()
            j += 1
          }
        case arr: Array[String] =>
          while (j < arr.length) {
            arr(j) = ""
            j += 1
          }
      }
      inputs.put(name, convertToTensor(name, tensor.info, Some(input), Some(tensorInfo.getShape.toSeq)))
      i += 1
    }
    InputsWrapper(inputs)
  }
}

object OnnxModel extends ModelLoader {

  val log: Logger = LoggerFactory.getLogger(this.getClass)
  val config: Config = ConfigFactory.load()

  private lazy val env = OrtEnvironment.getEnvironment()
  private lazy val opts = {
    val obj = new SessionOptions()

    // optimization level
    val level = config.getString("onnxruntime.optimization-level") match {
      case "no"       => SessionOptions.OptLevel.NO_OPT
      case "basic"    => SessionOptions.OptLevel.BASIC_OPT
      case "extended" => SessionOptions.OptLevel.EXTENDED_OPT
      case _          => SessionOptions.OptLevel.ALL_OPT
    }
    log.info(s"ONNXRuntime optimization level: $level")
    obj.setOptimizationLevel(level)

    // execution mode
    val mode = config.getString("onnxruntime.execution-mode") match {
      case "parallel" => SessionOptions.ExecutionMode.PARALLEL
      case _          => SessionOptions.ExecutionMode.SEQUENTIAL
    }
    log.info(s"ONNXRuntime execution mode: $mode")
    obj.setExecutionMode(mode)

    // execution backend
    val gpu = sys.props.getOrElse("gpu", "false").toLowerCase match {
      case "true" | "1" => true
      case _            => false
    }
    val backend = if (gpu) "cuda" else config.getString("onnxruntime.backend").toLowerCase
    log.info(s"ONNXRuntime execution backend: $backend")
    try {
      backend match {
        case "cuda" => obj.addCUDA(config.getInt("onnxruntime.device-id"))
        case "dnnl" => obj.addDnnl(true)
        case "tensorrt" => obj.addTensorrt(config.getInt("onnxruntime.device-id"))
        case "directml" => obj.addDirectML(config.getInt("onnxruntime.device-id"))
        case _ =>
          val numThreads = config.getInt("onnxruntime.cpu-num-threads")
          obj.setInterOpNumThreads(if (numThreads == -1) Utils.getNumCores else numThreads)
          obj.setIntraOpNumThreads(if (numThreads == -1) Utils.getNumCores else numThreads)
      }
    } catch {
      case ex: OrtException =>
        log.error(s"Failed to set execution backend '$backend', then try to use the default CPU", ex)
      case ex: Throwable    => throw ex
    }

    obj.setLoggerId(config.getString("onnxruntime.logger-id"))
    obj.setSessionLogLevel(OrtLoggingLevel.mapFromInt(config.getInt("onnxruntime.logging-level")))

    obj
  }

  def load(path: Path,
           modelName: String,
           modelVersion: String,
           config: Option[Config])(implicit ec: ExecutionContext): OnnxModel = {
    try {
      val modelPath = path.toAbsolutePath.toString
      val session = env.createSession(modelPath, opts)
      new OnnxModel(session, env, modelName, modelVersion, config)
    } catch {
      case ex: java.lang.UnsatisfiedLinkError => throw OnnxRuntimeLibraryNotFoundError(ex.getMessage)
      case ex: Throwable                      => throw ex
    }
  }
}
