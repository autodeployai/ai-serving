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

import scala.collection.concurrent.TrieMap

/**
 * Holds multiple versions of the specified model.
 *
 * @param modelName Model name specified
 */
class ModelCache(val modelName: String) extends AutoCloseable {
  val models:  TrieMap[String, PredictModel] = TrieMap.empty
  var latestVersion: Option[String] = None

  def this(modelName: String, latestVersion: Option[String]) = {
    this(modelName)
    this.latestVersion = latestVersion
  }

  def put(version: String, model: PredictModel): ModelCache = {
    models.put(version, model)
    this
  }

  def withLatestVersion(latestVersion: String): ModelCache = {
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

  def getOrLoad(modelVersion: Option[String] = None): Option[PredictModel] = {
    val versionOpt = modelVersion.orElse(latestVersion)
    if (versionOpt.isDefined) {
      val version = versionOpt.get
      val model = models.get(version)
      model orElse {
        val loaded = ModelManager.loadModel(modelName, version)
        models.put(version, loaded)
        Option(loaded)
      }
    } else {
      if (models.nonEmpty) {
        Option(models.last._2)
      } else None
    }
  }

  def getLatestVersion: Option[String] = latestVersion orElse {
    if (models.nonEmpty) Option(models.last._1) else None
  }

  def size: Int = models.size

  override def close(): Unit = {
    models.foreach(x => x._2.close())
  }
}