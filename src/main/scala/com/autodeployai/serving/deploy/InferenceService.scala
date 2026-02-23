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

import java.nio.file.{Files, Path, Paths}
import com.autodeployai.serving.errors.{InferTimeoutException, ModelNotFoundException, ModelTypeNotSupportedException}
import com.autodeployai.serving.model._
import com.autodeployai.serving.utils.{Constants, Utils}
import com.autodeployai.serving.utils.Utils.toOption
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Using

/**
 * Main entry of models validation, management and deployment.
 */
object InferenceService extends JsonSupport {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  // System config
  val config: Config = ConfigFactory.load()

  val homePath: String = config.getString("service.home") match {
    case "test" => Files.createTempDirectory("ai-serving-test-").toAbsolutePath.toString
    case value  => value
  }
  log.info(s"Service home located at: $homePath")

  private val modelsPath = Paths.get(homePath, Constants.ASSET_MODELS)
  log.info(s"Models locates at: $modelsPath")

  private val repositories: TrieMap[String, ModelRepository] = TrieMap.empty

  // Timeout scheduler
  private val timeoutScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
    (r: Runnable) => {
      val thread = new Thread(r, "ai-serving-timeout-scheduler")
      thread.setDaemon(true)
      thread
    })

  // A flag if the inference service is ready to response requests
  var isReady = false

  /**
   * Returns summaries of loaded models
   *
   * @return
   */
  def summaries: String = {
    val buf = new StringBuilder()
    val width = Array(20, 10)
    val format = String.format("| %%-%ds | %%-%ds |%%n", width(0), width(1))
    val split = "+" + "-".repeat(width(0) + 2) + "+" + "-".repeat(width(1) + 2) + "+\n"

    buf.append("Serving models summaries: \n")
    buf.append(split)
    buf.append(String.format(format, "Models", "Versions"))
    buf.append(split)
    val it = repositories.iterator
    while (it.hasNext) {
      val (modelName, modelCache) = it.next()
      buf.append(String.format(format, modelName, modelCache.versions.mkString(",")))
    }
    buf.append(split)
    buf.toString()
  }

  /**
   * Load all models into memory
   */
  def loadModels()(implicit ec: ExecutionContext): Unit = {
    log.info(s"Loading models under the directory: $modelsPath")

    Using(Files.list(modelsPath)) { files =>
      val it = files.iterator()
      while (it.hasNext) {
        val path = it.next()
        if (Files.isDirectory(path)) {
          val modelRepository = loadModel(path)
          modelRepository.foreach(x => repositories.put(x.modelName, x))
        }
      }
    }
    isReady = true
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
  def deploy(path: Path, modelType: String, modelName: String, deployConfig: Option[DeployConfig] = None)(implicit ec: ExecutionContext): Future[DeployResponse] = Future {
    var model: PredictModel = null
    try {
      val conf = deployConfig.map(_.toConfig)

      // create "models" dir if not exists
      if (Files.notExists(modelsPath)) {
        Files.createDirectories(modelsPath)
      }

      // check if a new model
      val modelPath = modelsPath.resolve(modelName)
      val modelMetadataPath = modelPath.resolve(Constants.MODEL_METADATA_FILE)
      val isNewModel = Files.notExists(modelMetadataPath)
      val modelMetadata = if (isNewModel) {
        ModelMetadata(modelName)
      } else {
        loadModelMetadata(modelPath).map(x => x.withUpdateAt().withLatestVersion()).getOrElse(ModelMetadata(modelName))
      }

      // version info
      val modelVersion = modelMetadata.latestStrVersion
      val versionPath = modelPath.resolve(modelVersion)

      // create "modelName" dir if not exists
      if (Files.notExists(modelPath)) {
        Files.createDirectory(modelPath)
      }

      // create or update model metadata
      saveJson(modelMetadataPath, modelMetadata)

      // load model config
      val modelConfig = getModelConfig(modelPath)

      // create version metadata
      model = PredictModel.load(path, modelType, modelName, modelVersion, conf.orElse(modelConfig))
      val versionMetadataPath = versionPath.resolve(Constants.MODEL_VERSION_METADATA_FILE)
      val versionMetadata = model.toModelInfo.copy(
        version = Some(ModelVersion(modelVersion)),
        size = Utils.fileSize(path)
      )
      Files.createDirectory(versionPath)
      saveJson(versionMetadataPath, versionMetadata)

      // write config if it's specified
      if (conf.isDefined) {
        val configPath = if (isNewModel) modelPath.resolve(Constants.MODEL_CONFIG_FILE) else versionPath.resolve(Constants.MODEL_CONFIG_FILE)
        writeConfig(conf, configPath)
      }

      // create model file with a proper extension
      val modelFilePath = versionPath.resolve(getModelFile(modelType))
      Files.copy(path, modelFilePath)

      // put it back to the model repository
      val modelRepository = repositories.getOrElseUpdate(modelName, {
        new ModelRepository(modelName, modelConfig, Option(modelVersion))
      })
      modelRepository.put(modelVersion, model)

      // return response with name and version
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
  def predict(request: PredictRequest, modelName: String, modelVersion: Option[String])(implicit ec: ExecutionContext): Future[PredictResponse] = {
    val model = getOrLoadModel(modelName, modelVersion)
    val runOptions = model.newRunOptions()

    val futureResult = Future {
      model.predict(request, runOptions)
    }

    withTimeout(futureResult, model, runOptions)
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
  def predict(request: InferenceRequest, modelName: String, modelVersion: Option[String])(implicit ec: ExecutionContext): Future[InferenceResponse] = {
    val model = getOrLoadModel(modelName, modelVersion)
    val runOptions = model.newRunOptions()

    val futureResult = model.batchProcessorV2() match {
      case Some(batchProcessor) =>
        batchProcessor.predict(request, runOptions)
      case _ =>
        Future {
          model.predict(request, runOptions)
        }
    }

    withTimeout(futureResult, model, runOptions)
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
    val modelCache = repositories.get(modelName)
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
    val modelsPath = Paths.get(homePath, Constants.ASSET_MODELS)
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
   * Undeploy the specified model from ai-serving.
   *
   * NOTE: ONNX Runtime may cause a JVM crash due to its native C++ implementation if a session is closed while it is
   * still processing a request.
   *
   * @param modelName
   * @param ec
   * @return
   */
  def undeploy(modelName: String)(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val modelPath = ensureModelExists(modelName)
    Utils.deleteDirectory(modelPath)

    // remove it from cache
    repositories.remove(modelName).foreach(_.close())
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

  private def getModelConfig(modelPath: Path): Option[Config] = {
    val configPath = modelPath.resolve(Constants.MODEL_CONFIG_FILE)
    loadConfig(configPath)
  }

  private def getOrLoadModel(modelName: String, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): PredictModel = {
    val modelRepository = repositories.getOrElseUpdate(modelName, {
      val modelPath = modelsPath.resolve(modelName)
      if (Files.notExists(modelPath)) {
        throw ModelNotFoundException(modelName)
      }

      // Load the model config
      val modelConfig = getModelConfig(modelPath)

      new ModelRepository(modelName, modelConfig, getLatestVersion(modelName))
    })

    val model = modelRepository.get(modelVersion)
    model.getOrElse {
      val versionOpt = modelVersion.orElse(modelRepository.getLatestVersion)
      versionOpt.map(version => {
        val loaded = loadModel(modelName, version, modelRepository.config)
        modelRepository.put(version, loaded)
        loaded
      }).getOrElse(throw ModelNotFoundException(modelName, modelVersion))
    }
  }

  private def loadModel(modelName: String, modelVersion: String, modelConfig: Option[Config])(implicit ec: ExecutionContext): PredictModel = {
    val versionPath = Paths.get(homePath, Constants.ASSET_MODELS, modelName, modelVersion)
    val (modelObjectPath, modelType) = getModelObjectPath(versionPath)
    if (modelObjectPath != null) {
      try {
        // Load the version config
        val versionConfig = getModelConfig(versionPath)

        PredictModel.load(modelObjectPath, modelType, modelName, modelVersion, versionConfig.orElse(modelConfig))
      } catch {
        case ex: Exception =>
          log.error(s"Failed to load model $modelName with the version $modelVersion", ex)
          throw ex
      }
    } else {
      throw ModelNotFoundException(modelName, Option(modelVersion))
    }
  }

  private def getLatestVersion(modelName: String): Option[String] = {
    loadModelMetadata(modelName).map(_.latestStrVersion)
  }

  private def loadModelMetadataWithVersion(modelName: String, modelPath: Path, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): ModelMetadata = {
    val modelMetadata = loadModelMetadata(modelPath).getOrElse(ModelMetadata(modelName))
    if (modelVersion.isDefined) {
      // If the specified version was not loaded yet, try to load the model
      val modelInfo = loadModelInfo(modelPath, modelVersion.get).getOrElse(getOrLoadModel(modelName, modelVersion).toModelInfo)
      modelMetadata.copy(versions = Some(Seq(modelInfo)))
    } else {
      // Collect all loaded models
      val modelCache = repositories.get(modelName)
      val versions = modelCache.map(_.models.map(x => x._2.toModelInfo).toSeq)
      modelMetadata.copy(versions = versions)
    }
  }

  private def loadModelMetadataWithVersionV2(modelName: String, modelPath: Path, modelVersion: Option[String] = None)(implicit ec: ExecutionContext): ModelMetadataV2 = {
    val modelMetadata = loadModelMetadata(modelPath)

    // Select the latest version if it's not specified
    val version: Option[String] = modelVersion.orElse(modelMetadata.map(_.latestStrVersion).orElse(
      repositories.get(modelName).flatMap(_.getLatestVersion)
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
    loadModelMetadata(Paths.get(homePath, Constants.ASSET_MODELS, modelName))
  }

  /**
   *  Loads the model metadata
   * @param modelPath The model path
   * @return
   */
  private def loadModelMetadata(modelPath: Path): Option[ModelMetadata] = {
    val modelMetadataPath = modelPath.resolve(Constants.MODEL_METADATA_FILE)
    try {
      if (Files.exists(modelMetadataPath)) {
        Some(loadJson[ModelMetadata](modelMetadataPath))
      } else None
    } catch {
      case ex: Exception =>
        log.error(s"Failed to load the model metadata located at $modelPath caused: ${ex.getMessage}")
        None
    }
  }

  private def loadModelInfo(modelPath: Path, version: String): Option[ModelInfo] = {
    val versionMetadataPath = modelPath.resolve(Paths.get(version, Constants.MODEL_VERSION_METADATA_FILE))
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
      case ex: Exception =>
        log.error(s"Failed to load the version metadata located at $versionMetadataPath", ex)
        None
    }
  }

  private def loadConfig(configPath: Path): Option[Config] = {
    try {
      if (Files.exists(configPath)) {
        Option(ConfigFactory.parseFile(configPath.toFile))
      } else None
    } catch {
      case ex: Exception =>
        log.error(s"Failed to load config located at $configPath", ex)
        None
    }
  }

  private def writeConfig(config: Option[Config], configPath: Path): Unit = {
    try {
      if (config.isDefined) {
        val conf = config.get
        val options = ConfigRenderOptions.defaults()
          .setJson(false)      // Use HOCON format (default, for human readability)
          .setFormatted(true)  // Pretty-print the output
          .setOriginComments(false); // Do not add comments about origin

        val configString = conf.root().render(options)
        Files.write(configPath, configString.getBytes)
        ()
      }
    } catch {
      case ex: Exception =>
        log.error(s"Failed to write config located at $configPath", ex)
    }
  }

  /**
   * Load models under the specified path
   * @param modelPath
   * @return
   */
  private def loadModel(modelPath: Path)(implicit ec: ExecutionContext): Option[ModelRepository] = {
    val modelName = modelPath.getFileName.toString

    // Load the model config
    val modelConfig = getModelConfig(modelPath)

    // Create model repository with the specified model name
    val modelRepository = new ModelRepository(modelName, modelConfig)

    val versions = ArrayBuffer.empty[String]
    Using(Files.list(modelPath)) { files =>
      val it = files.iterator()
      while (it.hasNext) {
        val path = it.next()
        if (Files.isDirectory(path)) {
          val modelVersion = path.getFileName.toString
          versions += modelVersion
          val (modelObjectPath, modelType) = getModelObjectPath(path)
          if (modelObjectPath != null) {
            try {
              log.info(s"Loading model: $modelName with the version $modelVersion")

              // Load the version config
              val versionConfig = getModelConfig(path)

              val model = PredictModel.load(modelObjectPath, modelType, modelName, modelVersion, versionConfig.orElse(modelConfig))
              modelRepository.put(modelVersion, model)
            } catch {
              case e: Exception =>
                log.error(s"Failed to load model $modelName with the version $modelVersion caused: $e")
            }
          }
        }
      }
    }

    if (modelRepository.size > 0) {
      val modelMetadata = loadModelMetadata(modelPath)
      Some(modelRepository.withLatestVersion(modelMetadata.map(_.latestStrVersion).getOrElse(versions.max)))
    } else None
  }

  private def getModelObjectPath(versionPath: Path): (Path, String) = {
    val modelFilePath = versionPath.resolve(Constants.MODEL_FILE)
    val versionMetadataPath = versionPath.resolve(Constants.MODEL_VERSION_METADATA_FILE)
    val modelInfo = loadModelInfo(versionMetadataPath)
    if (Files.exists(modelFilePath) && modelInfo.isDefined) {
      (modelFilePath, modelInfo.get.`type`)
    } else {
      val pmmlPath = versionPath.resolve(Constants.MODEL_PMML_FILE)
      if (Files.exists(pmmlPath)) {
        (pmmlPath, Constants.MODEL_TYPE_PMML)
      } else {
        val onnxPath = versionPath.resolve(Constants.MODEL_ONNX_FILE)
        if (Files.exists(onnxPath)) {
          (onnxPath, Constants.MODEL_TYPE_ONNX)
        } else {
          (null, null)
        }
      }
    }
  }

  private def getModelFile(modelType: String): String = modelType match {
    case Constants.MODEL_TYPE_PMML =>
      Constants.MODEL_PMML_FILE
    case Constants.MODEL_TYPE_ONNX =>
      Constants.MODEL_ONNX_FILE
    case _      => throw ModelTypeNotSupportedException(toOption(modelType))
  }


  /**
   * Apply timeout to a request if configured
   *
   * @param future
   * @param modelName
   * @param modelVersion
   * @param ec
   * @tparam T
   * @return
   */
  private def withTimeout[T](future: Future[T], model: PredictModel, runOptions: Option[RunOptions])(implicit ec: ExecutionContext): Future[T] = {
    val timeout = model.timeout
    if (timeout > 0) {
      val promise = Promise[T]()
      val timeoutFuture = timeoutScheduler.schedule(
        new Runnable {
          override def run(): Unit = {
            if (!promise.isCompleted) {
              runOptions.foreach(_.terminate())
              promise.tryFailure(InferTimeoutException(model.modelName, Option(model.modelVersion), timeout))
            }
          }
        },
        timeout,
        TimeUnit.MILLISECONDS
      )
      future.onComplete { result =>
        timeoutFuture.cancel(false)
        promise.tryComplete(result)
      }
      promise.future
    } else future
  }
}
