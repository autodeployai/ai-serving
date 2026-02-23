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

import java.nio.file.Path
import com.autodeployai.serving.errors.{InputNotSupportedException, InvalidModelException}
import com.autodeployai.serving.model.{DataType, Field, InferenceRequest, InferenceResponse, PredictRequest, PredictResponse, RecordSpec, ResponseOutput}
import com.autodeployai.serving.utils.{Constants, DataUtils, Utils}
import com.typesafe.config.Config
import org.pmml4s.common.{BooleanType, Closure, GenericInterval, IntegerType, NumericType, RealType, StringType}
import org.pmml4s.data.{DataVal, LongVal, Series}
import org.pmml4s.metadata.AttributeType
import org.pmml4s.model.Model
import org.slf4j.{Logger, LoggerFactory}

import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext
import scala.util.Random

/**
 * Supports the model of Predictive Model Markup Language (PMML)
 */
class PmmlModel(val model: Model,
                override val modelName: String,
                override val modelVersion: String,
                override val config: Option[Config]) extends PredictModel {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  // Inference timeout
  override val timeout: Long = parseTimeout()

  private val inputSchema = model.inputSchema

  private val outputTypes = model.outputFields.map(x => x.name -> x.dataType).toMap

  // Start the warmup process if it's configured properly.
  warmup()

  override def predict(request: PredictRequest, options: Option[RunOptions]): PredictResponse = {
    val filter = request.filter.orNull

    val result = if (request.X.records.isDefined) {
      RecordSpec(records = request.X.records.map(records => {
        records.map(record => {
          checkCancelled(options)
          val outputs = model.predict(Series.fromMap(record, inputSchema))
          outputs.filter(filter).asMap
        })
      }))
    } else {
      val columns = request.X.columns.get
      var outputColumns: Seq[String] = null
      val outputData = request.X.data.map(data => {
        data.map(values => {
          checkCancelled(options)
          val record = columns.zip(values).toMap
          val output = model.predict(Series.fromMap(record, inputSchema))
          val finalOutput = output.filter(filter)
          if (outputColumns == null) {
            outputColumns = finalOutput.columns.toSeq
          }
          finalOutput.asSeq
        })
      })
      RecordSpec(columns = Some(outputColumns), data = outputData)
    }

    PredictResponse(result)
  }

  override def predict(request: InferenceRequest, options: Option[RunOptions]): InferenceResponse = {
    val filter = request.outputs.map(x => x.map(y => y.name)).orNull

    val names = request.inputs.map(x => x.name)
    val data = request.inputs.map(x => x.data match {
      case arr: Array[_]      => arr
      case buffer: ByteBuffer =>
        DataUtils.convertToArray(buffer, DataType.withName(x.datatype))
      case _ =>
        throw InputNotSupportedException(x.name, x.datatype)
    })

    // Check if the parameter raw_output is specified, it's only available for grpc endpoint
    val rawOutput = request.parameters.flatMap(x => x.get("raw_output").map {
      case b: Boolean => b
      case _ => false
    }).getOrElse(false)

    // Get the maximum value as the final length
    val size = data.map(x => x.length).max

    val series = Array.ofDim[Series](size)
    var outputColumns: Array[String] = Array.empty

    var i = 0
    while (i < size) {
      val record = names.zip(data.map(x =>
        if (i < x.length) x(i) else null
      )).toMap
      checkCancelled(options)
      val output = model.predict(Series.fromMap(record, inputSchema))
      val finalOutput = output.filter(filter)
      if (outputColumns.isEmpty) {
        outputColumns = finalOutput.columns
      }
      series(i) = finalOutput
      i += 1
    }

    val columnsCount = outputColumns.length
    val outputs = Seq.newBuilder[ResponseOutput]
    outputs.sizeHint(columnsCount)
    var j = 0
    while (j < columnsCount) {
      checkCancelled(options)
      val dataType = outputTypes(outputColumns(j))
      val data = dataType match {
        case IntegerType =>
          val tensor = Array.ofDim[Long](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getLong(j)
            i += 1
          }
          if (rawOutput) DataUtils.convertToByteBuffer(tensor) else tensor
        case _: NumericType =>
          val tensor = Array.ofDim[Double](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getDouble(j)
            i += 1
          }
          if (rawOutput) DataUtils.convertToByteBuffer(tensor) else tensor
        case BooleanType =>
          val tensor = Array.ofDim[Boolean](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getBoolean(j)
            i += 1
          }
          if (rawOutput) DataUtils.convertToByteBuffer(tensor) else tensor
        case _ =>
          val tensor = Array.ofDim[String](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getString(j)
            i += 1
          }
          // Ignore the flag of raw output
          tensor
      }
      outputs += ResponseOutput(name=outputColumns(j),
        shape=Seq(size),
        datatype=Utils.dataTypeV1ToV2(dataType.toString),
        parameters=None,
        data=data)
      j += 1
    }

    InferenceResponse(
      model_name=modelName,
      model_version=Option(modelVersion),
      id=request.id,
      parameters=request.parameters,
      outputs=outputs.result()
    )
  }

  /**
   * Create an object of prediction options
   *
   * @return
   */
  override def newRunOptions(): Option[RunOptions] = {
    if (timeout > 0 ) Some(new CancelOptions) else None
  }

  override def `type`(): String = "PMML"

  override def runtime(): String = "pmml4s"

  override def serialization(): String = "pmml"

  override def formatVersion(): Option[String] = Option(model.version)

  override def algorithm(): Option[String] = Option(model.modelElement.toString)

  override def functionName(): Option[String] = Option(model.functionName.toString)

  override def description(): Option[String] = model.header.description

  override def app(): Option[String] = model.header.application.map(_.name)

  override def appVersion(): Option[String] = model.header.application.flatMap(_.version)

  override def copyright(): Option[String] = model.header.copyright

  override def inputs(): Seq[Field] = model.inputFields.toSeq.map(x => toField(x))

  override def targets(): Seq[Field] = model.targetFields.toSeq.map(x => toField(x))

  override def outputs(): Seq[Field] = model.outputFields.toSeq.map(x => toField(x))

  override def redundancies(): Seq[Field] = model.dataDictionary.fields.map(_.name).toSet.diff(model.inputNames ++ model.targetNames toSet).toSeq.map(
    x => toField(model.field(x))
  )

  override def close(): Unit = ()

  def toField(field: org.pmml4s.metadata.Field): Field =
    Field(field.name, field.dataType.toString, Option(field.opType.toString), values = Utils.toOption(field.valuesAsString))

  override def warmup(): Unit = {
    val (warmupCount, warmupDataType) = parseWarmup()
    if (warmupCount > 0) {
      log.info(s"Warmup for the model $modelName:$modelVersion initialized: warmup-count=$warmupCount, warmup-data-type=$warmupDataType")
      val start = System.currentTimeMillis()
      var i = 0
      var nSuccess = 0
      warmupDataType match {
        case Constants.CONFIG_WARMUP_DATA_TYPE_ZERO =>
          try {
            val zeroRecord = getZeroData
            while (i < warmupCount) {
              val r = model.predict(zeroRecord)
              if (!r.anyMissing) {
                nSuccess += 1
              }
              i += 1
            }
          } catch {
            case ex: Exception => log.warn("Warmup failed for the model ${modelName}:${modelVersion} when making prediction against the zero data", ex)
          }
        case _ =>
          while (i < warmupCount) {
            try {
              val randomRecord = getRandomData
              val r = model.predict(randomRecord)
              if (!r.anyMissing) {
                nSuccess += 1
              }
            } catch {
              case ex: Exception =>
                log.warn("Warmup failed for the model ${modelName}:${modelVersion} when making prediction against a random data", ex)
            }
            i += 1
          }
      }

      log.info(s"Warmup the model $modelName:$modelVersion finished in ${System.currentTimeMillis() - start} milliseconds: $i submitted, $nSuccess succeeded")
    }
  }

  private def getZeroData: Series = {
    val record = inputSchema.map(x => x.dataType match {
      case IntegerType  => LongVal(0L)
      case RealType     => DataVal.`0.0`
      case BooleanType  => DataVal.FALSE
      case StringType   => DataVal.EmptyString
      case _: NumericType => DataVal.`0.0`
      case _              => DataVal.NULL
    })
    Series.fromSeq(record, inputSchema)
  }

  private def getRandomData: Series = {
    val random = new Random
    val inputFields = model.inputFields

    val record = inputFields.map(x => x.attrType match {
      case AttributeType.Continuous =>
        if (x.intervals.isEmpty) {
          if (x.isReal) random.nextDouble() else random.nextInt()
        } else {
          val interval = x.intervals(random.nextInt(x.intervals.length))
          interval match {
            case GenericInterval(left, right, closure) =>
              closure match {
                case Closure.openOpen | Closure.openClosed =>
                  random.between(left + (right - left) / 2, right)
                case Closure.closedOpen | Closure.closedClosed =>
                  random.between(left, right)
              }
            case _ =>
              random.nextDouble()
          }
        }
      case AttributeType.Categorical =>
        if (x.validValues.length > 0 ) {
          x.validValues(random.nextInt(x.validValues.length))
        } else {
          if (x.dataType.isNumeric) random.nextDouble() else ""
        }
      case _ => ""
    })
    Series.fromArray(record, inputSchema)
  }
}

object PmmlModel extends ModelLoader {

  def load(path: Path,
           modelName: String,
           modelVersion: String,
           config: Option[Config])(implicit ec: ExecutionContext): PmmlModel = {
    try {
      val model = Model.fromPath(path)
      new PmmlModel(model, modelName, modelVersion, config)
    } catch {
      case ex: Throwable => throw InvalidModelException("PMML", ex.getMessage)
    }
  }
}