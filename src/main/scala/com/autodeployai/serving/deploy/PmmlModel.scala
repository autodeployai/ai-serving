/*
 * Copyright (c) 2019-2025 AutoDeployAI
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
import com.autodeployai.serving.errors.InvalidModelException
import com.autodeployai.serving.model.{Field, InferenceRequest, InferenceResponse, PredictRequest, PredictResponse, RecordSpec, ResponseOutput}
import com.autodeployai.serving.utils.Utils
import org.pmml4s.common.{BooleanType, IntegerType, NumericType}
import org.pmml4s.data.Series
import org.pmml4s.model.Model

/**
 * Supports the model of Predictive Model Markup Language (PMML)
 */
class PmmlModel(val model: Model) extends PredictModel {

  private val inputSchema = model.inputSchema

  private val outputTypes = model.outputFields.map(x => x.name -> x.dataType).toMap

  override def predict(request: PredictRequest, grpc: Boolean): PredictResponse = {
    val filter = request.filter.orNull

    val result = if (request.X.records.isDefined) {
      RecordSpec(records = request.X.records.map(records => {
        records.map(record => {
          val outputs = model.predict(Series.fromMap(record, inputSchema))
          outputs.filter(filter).asMap
        })
      }))
    } else {
      val columns = request.X.columns.get
      var outputColumns: Seq[String] = null
      val outputData = request.X.data.map(data => {
        data.map(values => {
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

  override def predict(request: InferenceRequest, grpc: Boolean): InferenceResponse = {
    val filter = request.outputs.map(x => x.map(y => y.name)).orNull

    val names = request.inputs.map(x => x.name)
    val data = request.inputs.map(x => x.data.asInstanceOf[Array[_]])

    // Suppose all inputs have the same shape
    val size = if (data.nonEmpty) data.head.length else 0

    val series = Array.ofDim[Series](size)
    var outputColumns: Array[String] = Array.empty

    var i = 0
    while (i < size) {
      val record = names.zip(data.map(x => x(i))).toMap
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
      val dataType = outputTypes(outputColumns(j))
      val data = dataType match {
        case IntegerType =>
          val tensor = Array.ofDim[Long](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getLong(j)
            i += 1
          }
          tensor
        case _: NumericType =>
          val tensor = Array.ofDim[Double](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getDouble(j)
            i += 1
          }
          tensor
        case BooleanType =>
          val tensor = Array.ofDim[Boolean](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getBoolean(j)
            i += 1
          }
          tensor
        case _ =>
          val tensor = Array.ofDim[String](size)
          var i = 0
          while (i < size) {
            tensor(i) = series(i).getString(j)
            i += 1
          }
          tensor
      }
      outputs += ResponseOutput(name=outputColumns(j),
        shape=Array(size),
        datatype=Utils.dataTypeV1ToV2(dataType.toString),
        parameters=None,
        data=data)
      j += 1
    }

    InferenceResponse(id=request.id, parameters=None, outputs=outputs.result())
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

  private def toField(field: org.pmml4s.metadata.Field): Field =
    Field(field.name, field.dataType.toString, Option(field.opType.toString), values = Utils.toOption(field.valuesAsString))
}

object PmmlModel extends ModelLoader {

  def load(path: Path): PmmlModel = {
    try {
      val model = Model.fromPath(path)
      new PmmlModel(model)
    } catch {
      case ex: Throwable => throw InvalidModelException("PMML", ex.getMessage)
    }
  }

}