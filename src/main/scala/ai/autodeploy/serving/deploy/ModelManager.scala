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

import java.nio.file.{Files, Path, Paths}

import ai.autodeploy.serving.errors.ModelNotFoundException
import ai.autodeploy.serving.model._
import ai.autodeploy.serving.utils.Utils
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

/**
 * Main entry of models validation, management and deployment.
 */
object ModelManager extends JsonSupport {

  val log = LoggerFactory.getLogger(this.getClass)

  val config = ConfigFactory.load()

  val ASSET_MODELS = "models"
  val MODEL_METADATA_FILE = "model.json"
  val MODEL_VERSION_METADATA_FILE = "version.json"
  val MODEL_FILE = "model"
  val HOME_PATH = config.getString("service.home") match {
    case "test" => Files.createTempDirectory("ai-serving-test-").toAbsolutePath.toString
    case value  => value
  }
  log.info(s"Service home located: ${HOME_PATH}")

  val modelPool: TrieMap[String, ModelCache] = TrieMap.empty

  /**
   * Validates an input model
   *
   * @param path
   * @param modelType
   * @param ec
   * @return
   */
  def validate(path: Path, modelType: String)(implicit ec: ExecutionContext): Future[ModelInfo] = Future {
    val model = PredictModel.load(path, modelType)
    model.toModelInfo().copy(createdAt = None, version = None)
  }

  /**
   * Deploys an input model
   *
   * @param path
   * @param modelType
   * @param modelName
   * @param ec
   * @return
   */
  def deploy(path: Path, modelType: String, modelName: String)(implicit ec: ExecutionContext): Future[DeployResponse] = Future {
    val model = PredictModel.load(path, modelType)

    // create "models" dir if not exists
    val modelsPath = Paths.get(HOME_PATH, ASSET_MODELS)
    if (Files.notExists(modelsPath)) {
      Files.createDirectories(modelsPath)
    }

    // check if a new model
    val modelPath = modelsPath.resolve(modelName)
    val modelMetadataPath = modelPath.resolve(MODEL_METADATA_FILE)
    val isNewModel = Files.notExists(modelMetadataPath)
    val modelMetadata = if (isNewModel) {
      ModelMetadata(modelName)
    } else {
      loadModelMetadata(modelPath).withUpdateAt().withLatestVersion()
    }

    // create "model" dir if not exists
    if (Files.notExists(modelPath)) {
      Files.createDirectory(modelPath)
    }

    // create or update model metadata
    saveJson(modelMetadataPath, modelMetadata)

    // create version metadata
    val modelVersion = modelMetadata.latestVersion
    val versionPath = modelPath.resolve(modelVersion.toString)
    val versionMetadataPath = versionPath.resolve(MODEL_VERSION_METADATA_FILE)
    val versionMetadata = model.toModelInfo().copy(
      version = Some(modelVersion),
      hash = Utils.md5Hash(path),
      size = Utils.fileSize(path)
    )
    Files.createDirectory(versionPath)
    saveJson(versionMetadataPath, versionMetadata)

    // create model file
    val modelFilePath = versionPath.resolve(MODEL_FILE)
    Files.copy(path, modelFilePath)

    DeployResponse(modelName, modelVersion)
  }

  /**
   * Predicts an input payload using the specified model
   *
   * @param payload      The input payload to predict
   * @param modelName    The specified model name
   * @param modelVersion The specified model version, the latest version is used if not present
   * @param ec
   * @return
   */
  def predict(payload: PredictRequest, modelName: String, modelVersion: Option[Int] = None)(implicit ec: ExecutionContext): Future[PredictResponse] = Future {
    val model = getOrLoadModel(modelName, modelVersion)
    model.predict(payload)
  }

  /**
   * Returns metadata of the specified model
   *
   * @param modelName
   * @param modelVersion
   * @param ec
   * @return
   */
  def getMetadata(modelName: String, modelVersion: Option[Int] = None)(implicit ec: ExecutionContext): Future[ModelMetadata] = Future {
    val modelPath = ensureModelExists(modelName, modelVersion)
    loadModelMetadataWithVersion(modelPath, modelVersion)
  }

  /**
   * Returns metadata of all served models
   *
   * @param ec
   * @return
   */
  def getMetadataAll()(implicit ec: ExecutionContext): Future[List[ModelMetadata]] = Future {
    val modelsPath = Paths.get(HOME_PATH, ASSET_MODELS)
    if (Files.notExists(modelsPath)) {
      List.empty
    } else {
      val result = new ArrayBuffer[ModelMetadata]()
      val it = Files.newDirectoryStream(modelsPath).iterator()
      while (it.hasNext) {
        val dir = it.next()
        if (Files.isDirectory(dir)) {
          result += loadModelMetadataWithVersion(dir)
        }
      }

      result.toList
    }
  }

  /**
   * Undeploy the specified model from ai-serving
   *
   * @param modelName
   * @param ec
   * @return
   */
  def undeploy(modelName: String)(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val modelPath = ensureModelExists(modelName)
    Utils.deleteDirectory(modelPath)

    // remove it from cache
    modelPool.remove(modelName).map(_.close())
    true
  }

  private def ensureModelExists(modelName: String, modelVersion: Option[Int] = None): Path = {
    val modelPath = Paths.get(HOME_PATH, ASSET_MODELS, modelName)
    if (Files.notExists(modelPath)) {
      throw ModelNotFoundException(modelName)
    }

    modelVersion.foreach(x => {
      val versionPath = modelPath.resolve(x.toString)
      if (Files.notExists(versionPath)) {
        throw ModelNotFoundException(modelName, modelVersion)
      }
    })

    modelPath
  }

  def getOrLoadModel(modelName: String, modelVersion: Option[Int] = None): PredictModel = {
    val modelCache = modelPool.getOrElseUpdate(modelName, {
      val modelPath = Paths.get(HOME_PATH, ASSET_MODELS, modelName)
      if (Files.notExists(modelPath)) {
        throw ModelNotFoundException(modelName)
      }
      new ModelCache(modelName, getLatestVersion(modelName))
    })

    val model = modelCache.getOrLoad(modelVersion)
    model.getOrElse(throw ModelNotFoundException(modelName, modelVersion))
  }

  def loadModel(modelName: String, modelVersion: Int): PredictModel = {
    val versionPath = Paths.get(HOME_PATH, ASSET_MODELS, modelName, modelVersion.toString)
    val modelFilePath = versionPath.resolve(MODEL_FILE)
    val versionMetadataPath = versionPath.resolve(MODEL_VERSION_METADATA_FILE)

    val modelInfo = loadModelInfo(versionMetadataPath)
    PredictModel.load(modelFilePath, modelInfo.`type`)
  }

  def getLatestVersion(modelName: String): Int = {
    loadModelMetadata(modelName).latestVersion
  }

  def loadModelMetadataWithVersion(modelPath: Path, modelVersion: Option[Int] = None): ModelMetadata = {
    val modelMetadata = loadModelMetadata(modelPath)
    if (modelVersion.isDefined) {
      val version = loadModelInfo(modelPath, modelVersion.get)
      modelMetadata.copy(versions = Some(Seq(version)))
    } else {
      val versions = (1 to modelMetadata.latestVersion).map(x => loadModelInfo(modelPath, x))
      modelMetadata.copy(versions = Some(versions))
    }
  }

  def loadModelMetadata(modelName: String): ModelMetadata = {
    loadModelMetadata(Paths.get(HOME_PATH, ASSET_MODELS, modelName))
  }

  def loadModelMetadata(modelPath: Path): ModelMetadata = {
    val modelMetadataPath = modelPath.resolve(MODEL_METADATA_FILE)
    loadJson[ModelMetadata](modelMetadataPath)
  }

  def loadModelInfo(modelPath: Path, version: Int): ModelInfo = {
    val versionMetadataPath = modelPath.resolve(Paths.get(version.toString, MODEL_VERSION_METADATA_FILE))
    loadModelInfo(versionMetadataPath)
  }

  def loadModelInfo(versionMetadataPath: Path): ModelInfo = {
    loadJson[ModelInfo](versionMetadataPath)
  }
}

