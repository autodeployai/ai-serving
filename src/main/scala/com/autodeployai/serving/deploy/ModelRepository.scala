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

import com.autodeployai.serving.utils.Constants
import com.typesafe.config.Config
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

/**
 * Holds multiple versions of the specified model.
 *
 * @param modelName Model name specified
 * @param config    Model based configuration
 */
class ModelRepository(val modelName: String, val config: Option[Config]=None) extends AutoCloseable {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  // Multiple versions
  val models: TrieMap[String, PredictModel] = TrieMap.empty

  // Timeout for versions
  private val timeouts: TrieMap[String, FiniteDuration] = TrieMap.empty

  // The latest version that will vary anytime
  var latestVersion: Option[String] = None

  def this(modelName: String, config: Option[Config], latestVersion: Option[String]) = {
    this(modelName, config)
    this.latestVersion = latestVersion
  }

  def put(version: String, model: PredictModel): ModelRepository = {
    models.put(version, model)
    readTimeoutDuration(version).foreach(x => timeouts.put(version, x))
    this
  }

  def withLatestVersion(latestVersion: String): ModelRepository = {
    this.latestVersion = Option(latestVersion)
    this
  }

  def contains(modelVersion: Option[String] = None): Boolean = {
    val version = modelVersion.orElse(getLatestVersion)
    version.exists(x => models.contains(x))
  }

  def versions: Array[String] = {
    models.keySet.toArray
  }

  def get(modelVersion: Option[String] = None): Option[PredictModel] = {
    val version = modelVersion.orElse(getLatestVersion)
    version.flatMap(x => models.get(x))
  }

  def apply(modelVersion: Option[String] = None): PredictModel = {
    models(modelVersion.getOrElse(latestVersion.get))
  }

  def getLatestVersion: Option[String] = latestVersion orElse {
    if (models.nonEmpty) Option(models.last._1) else None
  }

  def size: Int = models.size

  override def close(): Unit = {
    models.foreach(x => x._2.close())
  }

  def getTimeoutDuration(modelVersion: Option[String] = None): Option[FiniteDuration] = {
    modelVersion.orElse(latestVersion).flatMap(x => timeouts.get(x))
  }

  private def readTimeoutDuration(version: String): Option[FiniteDuration] = {
    val model = models.get(version)
    val timeout = model.flatMap(_.config()).flatMap(x =>
      if (x.hasPath(Constants.CONFIG_REQUEST_TIMEOUT_MS)) Some(x.getLong(Constants.CONFIG_REQUEST_TIMEOUT_MS)) else None
    ).getOrElse(
      config.flatMap(x=> if (x.hasPath(Constants.CONFIG_REQUEST_TIMEOUT_MS)) Some(x.getLong(Constants.CONFIG_REQUEST_TIMEOUT_MS)) else None).getOrElse(0L)
    )
    if (timeout > 0) {
      log.info(s"Timeout configured for model $modelName:$version with the timeout ${timeout}ms")
      Some(FiniteDuration(timeout, TimeUnit.MILLISECONDS))
    } else None
  }
}