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
import com.autodeployai.serving.utils.Utils.toOption
import com.autodeployai.serving.errors.ModelTypeNotSupportedException
import com.autodeployai.serving.model.{Field, InferenceRequest, InferenceResponse, ModelInfo, PredictRequest, PredictResponse}
import com.autodeployai.serving.utils.{Constants, Utils}
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext

/**
 * Predictable model supporting `predict`
 */
trait Predictable {
  /**
   *  Predicting API v1
   * @param request The input payload to predict
   * @return
   */
  def predict(request: PredictRequest): PredictResponse

  /**
   * Predicting API v2
   * @param request The input payload to predict
   * @return
   */
  def predict(request: InferenceRequest): InferenceResponse
}

trait ModelLoader {
  def load(path: Path,
           modelName: String,
           modelVersion: String,
           config: Option[Config])(implicit ec: ExecutionContext): PredictModel
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

  def inputs(): Seq[Field] = Seq.empty

  def targets(): Seq[Field] = Seq.empty

  def outputs(): Seq[Field] = Seq.empty

  def redundancies(): Seq[Field] = Seq.empty

  def toModelInfo: ModelInfo = ModelInfo(
    `type` = `type`(),
    serialization = serialization(),
    runtime = runtime(),
    inputs = toOption(inputs()),
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

  def config(): Option[Config] = None

  def batchProcessorV2(): Option[BatchProcessor[InferenceRequest, InferenceResponse]] = None

  def modelName: String = ""

  def modelVersion: String = ""

  def createBatchProcessorV2(model: PredictModel, config: Option[Config])(implicit ec: ExecutionContext): Option[BatchProcessor[InferenceRequest, InferenceResponse]] = config match{
    case Some(conf) if model.isSupportBatch =>
      val maxBatchSize = if (conf.hasPath(Constants.CONFIG_MAX_BATCH_SIZE)) {
        conf.getInt(Constants.CONFIG_MAX_BATCH_SIZE)
      } else 0
      val maxBatchDelayMs = if (conf.hasPath(Constants.CONFIG_MAX_BATCH_DELAY_MS)) {
        conf.getLong(Constants.CONFIG_MAX_BATCH_DELAY_MS)
      } else 0

      if (maxBatchSize > 1 && maxBatchDelayMs > 0L) {
        Some(new BatchProcessorV2(model, maxBatchSize, maxBatchDelayMs))
      } else None
    case _ => None
  }

  def warmup(): Unit = ()

  def warmupConfig(): (Int, String) = config() match {
    case Some(conf) =>
      val warmupCount = if (conf.hasPath(Constants.CONFIG_WARMUP_COUNT)) conf.getInt(Constants.CONFIG_WARMUP_COUNT) else 0
      val warmupDataType = if (conf.hasPath(Constants.CONFIG_WARMUP_DATA_TYPE)) conf.getString(Constants.CONFIG_WARMUP_DATA_TYPE) else Constants.CONFIG_WARMUP_DATA_TYPE_RANDOM
      (warmupCount, warmupDataType)
    case _ => (0, "")
  }

  private def isSupportBatch: Boolean = {
    Utils.supportDynamicBatch(inputs().map(_.shape))
  }
}

object PredictModel {

  def load(path: Path,
           modelType: String,
           modelName: String="",
           modelVersion: String="",
           config: Option[Config]=None)(implicit ec: ExecutionContext): PredictModel = modelType match {
    case Constants.MODEL_TYPE_PMML =>
      PmmlModel.load(path, modelName, modelVersion, config)
    case Constants.MODEL_TYPE_ONNX =>
      OnnxModel.load(path, modelName, modelVersion, config)
    case _      =>
      throw ModelTypeNotSupportedException(toOption(modelType))
  }
}



