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

package ai.autodeploy.serving.deploy

import java.nio.file.Path

import ai.autodeploy.serving.errors.InvalidModelException
import ai.autodeploy.serving.model.{Field, PredictRequest, PredictResponse, RecordSpec}
import ai.autodeploy.serving.utils.Utils
import org.pmml4s.data.Series
import org.pmml4s.model.Model

/**
 * Supports the model of Predictive Model Markup Language (PMML)
 */
class PmmlModel(val model: Model) extends PredictModel {

  override def predict(payload: PredictRequest): PredictResponse = {
    val filter = payload.filter.orNull

    val result = if (payload.X.records.isDefined) {
      RecordSpec(records = payload.X.records.map(records => {
        records.map(record => {
          val outputs = model.predict(Series.fromMap(record, model.inputSchema))
          outputs.filter(filter).toMap
        })
      }))
    } else {
      val columns = payload.X.columns.get
      var outputColumns: Seq[String] = null
      val outputData = payload.X.data.map(data => {
        data.map(values => {
          val record = columns.zip(values).toMap
          val outputs = model.predict(Series.fromMap(record, model.inputSchema))
          val finalOutputs = outputs.filter(filter)
          if (outputColumns == null) {
            outputColumns = finalOutputs.columns.toSeq
          }
          finalOutputs.toSeq
        })
      })
      RecordSpec(columns = Some(outputColumns), data = outputData)
    }

    PredictResponse(result)
  }

  override def `type`(): String = "PMML"

  override def runtime(): String = "PMML4S"

  override def serialization(): String = "pmml"

  override def formatVersion(): Option[String] = Option(model.version)

  override def algorithm(): Option[String] = Option(model.modelElement.toString)

  override def functionName(): Option[String] = Option(model.functionName.toString)

  override def description(): Option[String] = model.header.description

  override def app(): Option[String] = model.header.application.map(_.name)

  override def appVersion(): Option[String] = model.header.application.flatMap(_.version)

  override def copyright(): Option[String] = model.header.copyright

  override def predictors(): Seq[Field] = model.inputFields.toSeq.map(x => toField(x))

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