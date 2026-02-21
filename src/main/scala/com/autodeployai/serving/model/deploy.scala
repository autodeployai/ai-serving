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

package com.autodeployai.serving.model

import com.autodeployai.serving.AIServer.DeployConfigJsonFormat
import com.typesafe.config.{Config, ConfigFactory}

/**
 * Response for deploying request on successful run
 *
 * @param name    The specified servable name
 * @param version The deployed version starts from 1
 */
case class DeployResponse(name: String, version: String)

/**
 * Deployment configurations
 *
 * @param requestTimeoutMs  The request timeout
 * @param maxBatchSize      The max batch size
 * @param maxBatchDelayMs   The max batch delay in milliseconds
 * @param warmupCount       The specified warmup count
 * @param warmupDataType    One of "zero", "random"
 *                            - "zero": using all zero data
 *                            - "random": using random data
 */
case class DeployConfig(requestTimeoutMs: Option[Long]=None,
                        maxBatchSize: Option[Int]=None,
                        maxBatchDelayMs: Option[Long]=None,
                        warmupCount: Option[Int]=None,
                        warmupDataType: Option[String]=None) {

  def toConfig: Config = {
    ConfigFactory.parseString(DeployConfigJsonFormat.write(this).toString())
  }
}