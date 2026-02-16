/*
 * Copyright (c) 2026 AutoDeployAI
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

package com.autodeployai.serving.utils

/**
 * Contains all defined constants
 */
object Constants {
  val MODEL_CONFIG_FILE = "model.conf"
  val ASSET_MODELS = "models"
  val MODEL_METADATA_FILE = "model.json"
  val MODEL_VERSION_METADATA_FILE = "version.json"
  val MODEL_FILE = "model"
  val MODEL_PMML_FILE = "model.pmml"
  val MODEL_ONNX_FILE = "model.onnx"

  val MODEL_TYPE_PMML = "PMML"
  val MODEL_TYPE_ONNX = "ONNX"

  val CONFIG_REQUEST_TIMEOUT_MS = "request-timeout-ms"
  val CONFIG_MAX_BATCH_SIZE = "max-batch-size"
  val CONFIG_MAX_BATCH_DELAY_MS = "max-batch-delay-ms"
  val CONFIG_WARMUP_COUNT = "warmup-count"
  val CONFIG_WARMUP_DATA_TYPE = "warmup-data-type"

  val CONFIG_WARMUP_DATA_TYPE_RANDOM = "random"
  val CONFIG_WARMUP_DATA_TYPE_ZERO = "zero"
}
