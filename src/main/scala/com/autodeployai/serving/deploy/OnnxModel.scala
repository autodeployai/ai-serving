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

package com.autodeployai.serving.deploy

import java.nio._
import java.nio.file.Path
import com.autodeployai.serving.errors._
import com.autodeployai.serving.utils.DataUtils._
import com.autodeployai.serving.utils.Utils._
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime._
import com.autodeployai.serving.errors.{MissingValueException, OnnxRuntimeLibraryNotFoundError, ShapeMismatchException, UnknownDataTypeException}
import com.autodeployai.serving.model.{Field, PredictRequest, PredictResponse, RecordSpec}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Using}

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
class OnnxModel(val session: OrtSession, val env: OrtEnvironment) extends PredictModel {

  val inputTensors = session.getInputInfo.asScala.map[String, InputTensor](x =>
    x._1 -> InputTensor(x._2.getName, x._2.getInfo.asInstanceOf[TensorInfo]))

  override def predict(payload: PredictRequest): PredictResponse = {

    val requestedOutput = payload.filter.flatMap(x => toOption(x)).map(_.toSet.asJava).getOrElse(session.getOutputNames)

    val result: RecordSpec = if (payload.X.records.isDefined) {
      RecordSpec(records = payload.X.records.map(records => {
        records.map(record => {
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))

            result.asScala.map(x => {
              x.getKey -> x.getValue.getValue
            }).toMap
          } match {
            case Success(value)     => value
            case Failure(exception) => throw exception
          }
        })
      }))
    } else {
      val columns = payload.X.columns.get
      var outputColumns: Seq[String] = null
      val outputData = payload.X.data.map(data => {
        data.map(values => {
          val record = columns.zip(values).toMap
          Using.Manager { use =>
            val wrapper = use(createInputsWrapper(record))
            val result = use(session.run(wrapper.inputs, requestedOutput))
            val scalaResult = result.asScala
            if (outputColumns == null) {
              outputColumns = scalaResult.map(_.getKey).toSeq
            }
            scalaResult.map(_.getValue.getValue).toSeq
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

  override def `type`(): String = "ONNX"

  override def runtime(): String = "ONNX Runtime"

  override def serialization(): String = "onnx"

  override def predictors(): Seq[Field] = {
    session.getInputInfo.asScala.values.toSeq.map(x => {
      toField(x)
    })
  }

  override def outputs(): Seq[Field] = {
    session.getOutputInfo.asScala.values.toSeq.map(x => {
      toField(x)
    })
  }

  override def close(): Unit = {
    safeClose(session)
  }

  private def createInputsWrapper(record: Map[String, Any]): InputsWrapper = {
    val inputs = new java.util.HashMap[String, OnnxTensor](inputTensors.size)
    for ((name, tensor) <- inputTensors) {
      inputs.put(name, convertToTensor(name, tensor.info, record.get(name)))
    }
    InputsWrapper(inputs)
  }

  private def toField(node: NodeInfo): Field = {
    val name = node.getName
    node.getInfo match {
      case x: TensorInfo => Field(name, fieldType(x), shape = if (x.isScalar) None else Some(x.getShape.toList))
      case x             => Field(name, fieldType(x))
    }
  }

  private def fieldType(info: ValueInfo): String = info match {
    case x: TensorInfo   => s"tensor(${
      x.`type`.toString.toLowerCase()
    })"
    case x: MapInfo      => s"map(${
      x.keyType
    },${
      x.valueType
    })"
    case x: SequenceInfo => {
      val elementType = if (x.isSequenceOfMaps) {
        s"map(${
          x.mapInfo.keyType
        },${
          x.mapInfo.valueType
        })"
      } else s"${
        x.sequenceType
      }"
      s"seq(${
        elementType.toLowerCase
      })"
    }
  }


  private def convertToTensor(name: String, tensorInfo: TensorInfo, inputValue: Option[Any], inputShape: Option[Seq[_]] = None): OnnxTensor = inputValue match {
    case Some(value) => {
      import OnnxJavaType._
      val expectedShape = tensorInfo.getShape
      value match {
        case (v, s: Seq[_]) => {
          convertToTensor(name, tensorInfo, Option(v), Option(s))
        }
        case buffer: ByteBuffer => {
          val shape = inputShape.map(x => convertShape(x)).getOrElse(expectedShape)
          val convertedShape = if (isDynamicShape(expectedShape)) shape else expectedShape

          tensorInfo.`type` match {
            case FLOAT   => {
              OnnxTensor.createTensor(env, buffer.asFloatBuffer(), convertedShape)
            }
            case DOUBLE  => {
              OnnxTensor.createTensor(env, buffer.asDoubleBuffer(), convertedShape)
            }
            case INT8    => {
              OnnxTensor.createTensor(env, buffer, convertedShape)
            }
            case INT16   => {
              OnnxTensor.createTensor(env, buffer.asShortBuffer(), convertedShape)
            }
            case INT32   => {
              OnnxTensor.createTensor(env, buffer.asIntBuffer(), convertedShape)
            }
            case INT64   => {
              OnnxTensor.createTensor(env, buffer.asLongBuffer(), convertedShape)
            }
            case BOOL    => {
              ???
            }
            case STRING  => {
              ???
            }
            case UINT8   => {
              OnnxTensor.createTensor(env, buffer, convertedShape, OnnxJavaType.UINT8)
            }
            case UNKNOWN => {
              throw UnknownDataTypeException(name)
            }
          }
        }
        case _                  => {
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
            case FLOAT   => {
              val data = copyToBuffer[Float](intCount, value)
              OnnxTensor.createTensor(env, FloatBuffer.wrap(data), convertedShape)
            }
            case DOUBLE  => {
              val data = copyToBuffer[Double](intCount, value)
              OnnxTensor.createTensor(env, DoubleBuffer.wrap(data), convertedShape)
            }
            case INT8    => {
              val data = copyToBuffer[Byte](intCount, value)
              OnnxTensor.createTensor(env, ByteBuffer.wrap(data), convertedShape)
            }
            case INT16   => {
              val data = copyToBuffer[Short](intCount, value)
              OnnxTensor.createTensor(env, ShortBuffer.wrap(data), convertedShape)
            }
            case INT32   => {
              val data = copyToBuffer[Int](intCount, value)
              OnnxTensor.createTensor(env, IntBuffer.wrap(data), convertedShape)
            }
            case INT64   => {
              val data = copyToBuffer[Long](intCount, value)
              OnnxTensor.createTensor(env, LongBuffer.wrap(data), convertedShape)
            }
            case BOOL    => {
              val data = copyToBuffer[Boolean](intCount, value)
              OnnxTensor.createTensor(env, OrtUtil.reshape(data, convertedShape))
            }
            case STRING  => {
              val data = copyToBuffer[String](intCount, value)
              OnnxTensor.createTensor(env, data, convertedShape)
            }
            case UINT8   => {
              val data = copyToBuffer[Byte](intCount, value)
              OnnxTensor.createTensor(env, ByteBuffer.wrap(data), convertedShape, OnnxJavaType.UINT8)
            }
            case UNKNOWN => {
              throw UnknownDataTypeException(name)
            }
          }
        }
      }
    }
    case _           => throw MissingValueException(name, fieldType(tensorInfo), tensorInfo.getShape)
  }

  private def copyToBuffer[@specialized(Float, Double, Byte, Short, Int, Long, Boolean) T: ClassTag](len: Int, value: Any): Array[T] = {
    val data = Array.ofDim[T](len)

    val tag = implicitly[ClassTag[T]]
    tag.runtimeClass match {
      case java.lang.Float.TYPE   => copy[Float](data.asInstanceOf[Array[Float]], 0, value, anyToFloat)
      case java.lang.Double.TYPE  => copy[Double](data.asInstanceOf[Array[Double]], 0, value, anyToDouble)
      case java.lang.Byte.TYPE    => copy[Byte](data.asInstanceOf[Array[Byte]], 0, value, anyToByte)
      case java.lang.Short.TYPE   => copy[Short](data.asInstanceOf[Array[Short]], 0, value, anyToShort)
      case java.lang.Integer.TYPE => copy[Int](data.asInstanceOf[Array[Int]], 0, value, anyToInt)
      case java.lang.Long.TYPE    => copy[Long](data.asInstanceOf[Array[Long]], 0, value, anyToLong)
      case java.lang.Boolean.TYPE => copy[Boolean](data.asInstanceOf[Array[Boolean]], 0, value, anyToBoolean)
      case _                      => copy[String](data.asInstanceOf[Array[String]], 0, value, anyToString)
    }

    data
  }

  private def copy[T](data: Array[T], pos: Int, value: Any, converter: Any => T): Int = value match {
    case seq: Seq[_] => {
      var idx = pos
      seq.foreach {
        x =>
          idx = copy(data, idx, x, converter)
      }
      idx
    }
    case _           => {
      data.update(pos, converter(value))
      pos + 1
    }
  }

  private def convertShape(shape: Seq[_]): Array[Long] = {
    val result = Array.ofDim[Long](shape.length)
    shape.zipWithIndex.foreach(x =>result(x._2) = {
      x._1 match {
        case element: Long    => element
        case element: Number  => element.longValue()
        case _                => x.toString().toLong
      }
    })
    result
  }
}


object OnnxModel extends ModelLoader {

  val log = LoggerFactory.getLogger(this.getClass)
  val config = ConfigFactory.load()

  lazy val env = OrtEnvironment.getEnvironment()
  lazy val opts = {
    val obj = new SessionOptions()

    // optimization level
    val level = config.getString("onnxruntime.optimization-level") match {
      case "no"       => SessionOptions.OptLevel.NO_OPT
      case "basic"    => SessionOptions.OptLevel.BASIC_OPT
      case "extended" => SessionOptions.OptLevel.EXTENDED_OPT
      case _          => SessionOptions.OptLevel.ALL_OPT
    }
    log.info(s"ONNXRuntime optimization level: ${level}")
    obj.setOptimizationLevel(level)

    // execution mode
    val mode = config.getString("onnxruntime.execution-mode") match {
      case "parallel" => SessionOptions.ExecutionMode.PARALLEL
      case _          => SessionOptions.ExecutionMode.SEQUENTIAL
    }
    log.info(s"ONNXRuntime execution mode: ${mode}")
    obj.setExecutionMode(mode)

    // execution backend
    val gpu = (sys.props.getOrElse("gpu", "false").toLowerCase match {
      case "true" | "1" => true
      case _            => false
    })
    val backend = if (gpu) "cuda" else config.getString("onnxruntime.backend").toLowerCase
    log.info(s"ONNXRuntime execution backend: ${backend}")
    try {
      backend match {
        case "cuda" => obj.addCUDA(config.getInt("onnxruntime.device-id"))
        case "dnnl" => obj.addDnnl(true)
        case "tensorrt" => obj.addTensorrt(config.getInt("onnxruntime.device-id"))
        case "directml" => obj.addDirectML(config.getInt("onnxruntime.device-id"))
        case _ =>
      }
    } catch {
      case ex: OrtException => {
        log.error(s"Failed to set execution backend '${backend}', then try to use the default CPU", ex)
      }
      case ex: Throwable    => throw ex
    }

    obj
  }

  def load(path: Path): OnnxModel = {
    try {
      val modelPath = path.toAbsolutePath.toString
      val session = env.createSession(modelPath, opts)
      new OnnxModel(session, env)
    } catch {
      case ex: java.lang.UnsatisfiedLinkError => throw OnnxRuntimeLibraryNotFoundError(ex.getMessage)
      case ex: Throwable                      => throw ex
    }
  }

}
