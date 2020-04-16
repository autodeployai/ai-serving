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

import scala.collection.mutable.ArrayBuffer

/**
 * Holds multiple versions of the specified model.
 *
 * @param modelName
 */
class ModelCache(val modelName: String) extends AutoCloseable {
  val models: ArrayBuffer[Option[PredictModel]] = ArrayBuffer.empty
  var latestVersion: Int = 1
  ensureCapacity(latestVersion)

  def this(modelName: String, latestVersion: Int) = {
    this(modelName)
    this.latestVersion = latestVersion
  }

  def getOrLoad(modelVersion: Option[Int] = None): Option[PredictModel] = {
    val version = modelVersion.getOrElse(latestVersion)
    if (version > 0 && version <= models.length) {
      val model = models(version - 1)
      model.orElse(updateAt(version))
    } else {
      latestVersion = ModelManager.getLatestVersion(modelName)
      ensureCapacity(latestVersion)
      if (version > 0 && version <= latestVersion) {
        updateAt(version)
      } else None
    }
  }

  override def close(): Unit = {
    models.foreach(x => x.foreach(_.close()))
  }

  private def updateAt(version: Int): Option[PredictModel] = {
    val model = Option(ModelManager.loadModel(modelName, version))
    models.update(version - 1, model)
    model
  }

  private def ensureCapacity(size: Int): ModelCache = {
    if (size > models.size) {
      models.sizeHint(size)
      models ++= Array.fill(size - models.size)(None)
    }
    this
  }

}