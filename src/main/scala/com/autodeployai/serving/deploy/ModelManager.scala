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

import java.nio.file.{Files, Path, Paths}
import com.autodeployai.serving.errors.{ModelNotFoundException, ModelTypeNotSupportedException}
import com.autodeployai.serving.model._
import com.autodeployai.serving.utils.Utils
import com.autodeployai.serving.utils.Utils.toOption
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

/**
 * Main entry of models validation, management and deployment.
 */
object ModelManager extends JsonSupport {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  private val ASSET_MODELS = "models"
  private val MODEL_METADATA_FILE = "model.json"
  private val MODEL_VERSION_METADATA_FILE = "version.json"
  private val MODEL_FILE = "model"
  private val MODEL_PMML_FILE = "model.pmml"
  private val MODEL_ONNX_FILE = "model.onnx"

  val config: Config = ConfigFactory.load()
  val HOME_PATH: String = config.getString("service.home") match {
    case "test" => Files.createTempDirectory("ai-serving-test-").toAbsolutePath.toString
    case value  => value
  }
  log.info(s"Service home located at: ${HOME_PATH}")

  private val modelsPath = Paths.get(HOME_PATH, ASSET_MODELS)
  private val modelPool: TrieMap[String, ModelCache] = TrieMap.empty

  /**
   * Load all models into memory
   */
  def loadModels(): Unit = {
    log.info(s"Loading models under the directory: ${modelsPath}")

    val files = Files.list(modelsPath)
    val it = files.iterator()
    while (it.hasNext) {
      val path = it.next()
      if (Files.isDirectory(path)) {
        val modelCache = loadModel(path)
        modelCache.foreach(x => modelPool.put(x.modelName, x))
      }
    }
  }

  /**
   * Load models under the specified path
   * @param modelPath
   * @return
   */
  private def loadModel(modelPath: Path): Option[ModelCache] = {
    val modelName = modelPath.getFileName.toString
    val result = new ModelCache(modelName)

    log.info(s"Loading model: ${modelName}")

    val versions = ArrayBuffer.empty[String]
    val files = Files.list(modelPath)
    val it = files.iterator()
    while (it.hasNext) {
      val path = it.next()
      if (Files.isDirectory(path)) {
        val version = path.getFileName.toString
        versions += version
        val (modelObjectPath, modelType) = getModelObjectPath(path)
        if (modelObjectPath != null) {
          try {
            val model = PredictModel.load(modelObjectPath, modelType)
            result.put(version, model)
          } catch {
            case e: Exception => {
              log.error(s"Failed to load model ${modelName} with the version ${version} caused: ${e}")
            }
          }
        }
      }
    }

    if (result.size > 0) {
      val modelMetadata = loadModelMetadata(modelPath)
      Some(result.withLatestVersion(modelMetadata.map(_.latestStrVersion).getOrElse(versions.min)))
    } else None
  }

  private def getModelObjectPath(versionPath: Path): (Path, String) = {
    val modelFilePath = versionPath.resolve(MODEL_FILE)
    val versionMetadataPath = versionPath.resolve(MODEL_VERSION_METADATA_FILE)
    val modelInfo = loadModelInfo(versionMetadataPath)
    if (Files.exists(modelFilePath) && modelInfo.isDefined) {
      (modelFilePath, modelInfo.get.`type`)
    } else {
      val pmmlPath = versionPath.resolve(MODEL_PMML_FILE)
      if (Files.exists(pmmlPath)) {
        (pmmlPath, "PMML")
      } else {
        val onnxPath = versionPath.resolve(MODEL_ONNX_FILE)
        if (Files.exists(onnxPath)) {
          (onnxPath, "ONNX")
        } else {
          (null, null)
        }
      }
    }
  }

  private def getModelFile(modelType: String): String = modelType match {
    case "PMML" => MODEL_PMML_FILE
    case "ONNX" => MODEL_ONNX_FILE
    case _      => throw ModelTypeNotSupportedException(toOption(modelType))
  }

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
    model.toModelInfo.copy(createdAt = None, version = None)
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

    try {
      // create "models" dir if not exists
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
        loadModelMetadata(modelPath).map(x => x.withUpdateAt().withLatestVersion()).getOrElse(ModelMetadata(modelName))
      }

      // create "modelName" dir if not exists
      if (Files.notExists(modelPath)) {
        Files.createDirectory(modelPath)
      }

      // create or update model metadata
      saveJson(modelMetadataPath, modelMetadata)

      // create version metadata
      val modelVersion = modelMetadata.latestStrVersion
      val versionPath = modelPath.resolve(modelVersion)
      val versionMetadataPath = versionPath.resolve(MODEL_VERSION_METADATA_FILE)
      val versionMetadata = model.toModelInfo.copy(
        version = Some(ModelVersion(modelVersion)),
        hash = Utils.md5Hash(path),
        size = Utils.fileSize(path)
      )
      Files.createDirectory(versionPath)
      saveJson(versionMetadataPath, versionMetadata)

      // create model file with a proper extension
      val modelFilePath = versionPath.resolve(getModelFile(modelType))
      Files.copy(path, modelFilePath)

      // Put it back to the cache
      val modelCache = modelPool.getOrElseUpdate(modelName, {
        new ModelCache(modelName, Option(modelVersion))
      })
      modelCache.put(modelVersion, model)

      DeployResponse(modelName, modelVersion)
    } catch {
      case ex: Throwable =>
        Utils.safeClose(model)
        throw ex
    }
  }

  /**
   * Predicts an input payload using the specified model based on API V1
   *
   * @param request      The input payload to predict
   * @param modelName    The specified model name
   * @param modelVersion The specified model version, the latest version is used if not present
   * @param ec
   * @return
   */
  def predict(request: PredictRequest, modelName: String, modelVersion: Option[String], grpc: Boolean)(implicit ec: ExecutionContext): Future[PredictResponse] = Future {
    val model = getOrLoadModel(modelName, modelVersion)
    model.predict(request, grpc)
  }

  /**
   * Predicts an input payload using the specified model based on API V2
   *
   * @param request
   * @param modelName
   * @param modelVersion
   * @param ec
   * @return
   */
  def predict(request: InferenceRequest, modelName: String, modelVersion: Option[String], grpc: Boolean)(implicit ec: ExecutionContext): Future[InferenceResponse] = Future {
    val model = getOrLoadModel(modelName, modelVersion)
    val response = model.predict(request, grpc)
    response.withModelSpec(modelName, modelVersion)
  }

  /**
   * Returns metadata of the specified model
   *
   * @param modelName
   * @param modelVersion
   * @param ec
   * @return
   */
  def getMetadata(modelName: String, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): Future[ModelMetadata] = Future {
    val modelPath = ensureModelExists(modelName, modelVersion)
    loadModelMetadataWithVersion(modelName, modelPath, modelVersion)
  }

  /**
   * Returns metadata of the specified model
   *
   * @param modelName
   * @param modelVersion
   * @param ec
   * @return
   */
  def getMetadataV2(modelName: String, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): Future[ModelMetadataV2] = Future {
    val modelPath = ensureModelExists(modelName, modelVersion)
    loadModelMetadataWithVersionV2(modelName, modelPath, modelVersion)
  }

  /**
   * Checks if the specified model is ready to handle requests
   *
   * @param modelName
   * @param modelVersion
   * @param ec
   * @return
   */
  def isModelReady(modelName: String, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val modelCache = modelPool.get(modelName)
    val result = modelCache.exists(x => x.contains(modelVersion))
    if (!result) {
      ensureModelExists(modelName, modelVersion)
    }
    result
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
          result += loadModelMetadataWithVersion(dir.getFileName.toString, dir)
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
    modelPool.remove(modelName).foreach(_.close())
    true
  }

  private def ensureModelExists(modelName: String, modelVersion: Option[String] = None): Path = {
    val modelPath = modelsPath.resolve(modelName)
    if (Files.notExists(modelPath)) {
      throw ModelNotFoundException(modelName)
    }

    modelVersion.foreach(x => {
      val versionPath = modelPath.resolve(x)
      if (Files.notExists(versionPath)) {
        throw ModelNotFoundException(modelName, modelVersion)
      }
    })

    modelPath
  }

  private def getOrLoadModel(modelName: String, modelVersion: Option[String] = None): PredictModel = {
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

  def loadModel(modelName: String, modelVersion: String): PredictModel = {
    val versionPath = Paths.get(HOME_PATH, ASSET_MODELS, modelName, modelVersion)
    val (modelObjectPath, modelType) = getModelObjectPath(versionPath)
    if (modelObjectPath != null) {
      try {
        PredictModel.load(modelObjectPath, modelType)
      } catch {
        case e: Exception => {
          log.error(s"Failed to load model ${modelName} with the version ${modelVersion} caused: ${e}")
          throw e
        }
      }
    } else {
      throw ModelNotFoundException(modelName, Option(modelVersion))
    }
  }

  private def getLatestVersion(modelName: String): Option[String] = {
    loadModelMetadata(modelName).map(_.latestStrVersion)
  }

  private def loadModelMetadataWithVersion(modelName: String, modelPath: Path, modelVersion: Option[String] = None): ModelMetadata = {
    val modelMetadata = loadModelMetadata(modelPath).getOrElse(ModelMetadata(modelName))
    if (modelVersion.isDefined) {
      // If the specified version was not loaded yet, try to load the model
      val modelInfo = loadModelInfo(modelPath, modelVersion.get).getOrElse(getOrLoadModel(modelName, modelVersion).toModelInfo)
      modelMetadata.copy(versions = Some(Seq(modelInfo)))
    } else {
      // Collect all loaded models
      val modelCache = modelPool.get(modelName)
      val versions = modelCache.map(_.models.map(x => x._2.toModelInfo).toSeq)
      modelMetadata.copy(versions = versions)
    }
  }

  private def loadModelMetadataWithVersionV2(modelName: String, modelPath: Path, modelVersion: Option[String] = None): ModelMetadataV2 = {
    val modelMetadata = loadModelMetadata(modelPath)

    // Select the latest version if it's not specified
    val version: Option[String] = modelVersion.orElse(modelMetadata.map(_.latestStrVersion).orElse(
      modelPool.get(modelName).flatMap(_.getLatestVersion)
    ))
    val modelInfo = version.flatMap(x => loadModelInfo(modelPath, x)).getOrElse(getOrLoadModel(modelName, modelVersion).toModelInfo)
    ModelMetadataV2(name = modelName,
      versions = version.toSeq,
      platform = if (modelInfo.serialization == "onnx") "onnx_onnxv1" else "pmml_pmmlv4" ,
      inputs = modelInfo.inputs.map(x => x.map(y => MetadataTensor(
        name=y.name,
        datatype=Utils.dataTypeV1ToV2(y.`type`),
        shape=y.shape.getOrElse(Seq(-1))
      ))).getOrElse(Seq.empty),
      outputs = modelInfo.outputs.map(x => x.map(y => MetadataTensor(
        name=y.name,
        datatype=Utils.dataTypeV1ToV2(y.`type`),
        shape=y.shape.getOrElse(Seq(-1))
      ))).getOrElse(Seq.empty),
    )
  }

  /**
   *  Loads the model metadata
   * @param modelName The model name
   * @return
   */
  private def loadModelMetadata(modelName: String): Option[ModelMetadata] = {
    loadModelMetadata(Paths.get(HOME_PATH, ASSET_MODELS, modelName))
  }

  /**
   *  Loads the model metadata
   * @param modelPath The model path
   * @return
   */
  private def loadModelMetadata(modelPath: Path): Option[ModelMetadata] = {
    val modelMetadataPath = modelPath.resolve(MODEL_METADATA_FILE)
    try {
      if (Files.exists(modelMetadataPath)) {
        Some(loadJson[ModelMetadata](modelMetadataPath))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  private def loadModelInfo(modelPath: Path, version: String): Option[ModelInfo] = {
    val versionMetadataPath = modelPath.resolve(Paths.get(version, MODEL_VERSION_METADATA_FILE))
    loadModelInfo(versionMetadataPath)
  }

  /**
   *  Loads the metadata of versioned model with the specified path
   * @param versionMetadataPath
   * @return
   */
  private def loadModelInfo(versionMetadataPath: Path): Option[ModelInfo] = {
    try {
      if (Files.exists(versionMetadataPath)) {
        Option(loadJson[ModelInfo](versionMetadataPath))
      } else None
    } catch {
      case _: Exception => None
    }
  }

  def summaries: String = {
    val buf = new StringBuilder()
    val width = Array(20, 10)
    val format = String.format("| %%-%ds | %%-%ds |%%n", width(0), width(1))
    val split = "+" + "-".repeat(width(0) + 2) + "+" + "-".repeat(width(1) + 2) + "+\n"

    buf.append("Serving models summaries: \n")
    buf.append(split)
    buf.append(String.format(format, "Models", "Versions"))
    buf.append(split)
    val it = modelPool.iterator
    while (it.hasNext) {
      val (modelName, modelCache) = it.next()
      buf.append(String.format(format, modelName, modelCache.versions.mkString(",")))
    }
    buf.append(split)
    buf.toString()
  }
}

