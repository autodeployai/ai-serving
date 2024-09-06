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

import java.nio.file.Path
import com.autodeployai.serving.utils.Utils.toOption
import com.autodeployai.serving.errors.ModelTypeNotSupportedException
import com.autodeployai.serving.model.{Field, ModelInfo, PredictRequest, PredictResponse}


trait Predictable {
  def predict(payload: PredictRequest): PredictResponse
}

trait ModelLoader {
  def load(path: Path): PredictModel
}

trait PredictModel extends Predictable with AutoCloseable {

  def `type`(): String

  def serialization(): String

  def runtime(): String

  def formatVersion(): Option[String] = None

  def algorithm(): Option[String] = None

  def functionName(): Option[String] = None

  def description(): Option[String] = None

  def app(): Option[String] = None

  def appVersion(): Option[String] = None

  def copyright(): Option[String] = None

  def predictors(): Seq[Field] = Seq.empty

  def targets(): Seq[Field] = Seq.empty

  def outputs(): Seq[Field] = Seq.empty

  def redundancies(): Seq[Field] = Seq.empty

  def toModelInfo(): ModelInfo = ModelInfo(
    `type` = `type`(),
    serialization = serialization(),
    runtime = runtime(),
    predictors = toOption(predictors()),
    targets = toOption(targets()),
    outputs = toOption(outputs()),
    redundancies = toOption(redundancies()),
    algorithm = algorithm(),
    functionName = functionName(),
    description = description(),
    formatVersion = formatVersion(),
    app = app(),
    appVersion = appVersion(),
    copyright = copyright()
  )
}

object PredictModel {

  def load(path: Path, modelType: String): PredictModel = modelType match {
    case "PMML" => PmmlModel.load(path)
    case "ONNX" => OnnxModel.load(path)
    case "PFA"  => ???
    case _      => {
      throw ModelTypeNotSupportedException(toOption(modelType))
    }
  }
}



