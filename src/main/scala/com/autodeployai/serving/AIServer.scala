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

package com.autodeployai.serving

import akka.actor.ActorSystem
import akka.dispatch.MessageDispatcher
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.http.{Endpoints, EndpointsV2}
import com.autodeployai.serving.grpc.GrpcServer
import com.autodeployai.serving.utils.Utils
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.slf4j.{Logger, LoggerFactory}

/**
 * Entrypoint of AI serving services
 */
object AIServer extends Endpoints with EndpointsV2 {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  var config: Config = ConfigFactory.load()
  private val defaultFixedPoolSizePath = "akka.ai-dispatcher.thread-pool-executor.fixed-pool-size"
  if (config.hasPath(defaultFixedPoolSizePath)) {
    var numCores = config.getInt(defaultFixedPoolSizePath)
    if (numCores == -1) {
      val onnxBackend = if (config.hasPath("onnxruntime.backend")) config.getString("onnxruntime.backend").toLowerCase else "cpu"
      val onnxThreads = if (config.hasPath("onnxruntime.cpu-num-threads")) config.getInt("onnxruntime.cpu-num-threads") else -1

      if (onnxBackend == "cpu" && onnxThreads == -1) {
        log.warn("Please reserve sufficient CPU capacity for ONNX Runtime to prevent oversubscription when serving ONNX models on CPU.")
      }
      numCores = Utils.getNumCores
    }
    config = config.withValue(defaultFixedPoolSizePath, ConfigValueFactory.fromAnyRef(numCores))
    log.info(s"The fixed thread pool size ${numCores} being configured for processing requests")
  }

  implicit val system: ActorSystem = ActorSystem("AI-Serving", config)
  implicit val executionContext: MessageDispatcher = system.dispatchers.lookup("akka.ai-dispatcher")

  lazy val route: Route = up() ~ validate() ~ modelsV1() ~ modelV1() ~ modelVersionV1() ~
    healthLiveV2() ~ healthReadyV2() ~ modelReadyV2() ~ modelVersionReadyV2() ~ serverV2() ~ modelMetadataV2() ~ modelVersionMetadataV2() ~
    modelInferV2() ~ modelVersionInferV2()

  def start(args: Array[String]): Unit = {

    // load all models into memory
    InferenceService.loadModels()
    log.info(InferenceService.summaries)

    val host = config.getString("service.http.interface")
    val httpPort = config.getInt("service.http.port")
    val grpcPort = config.getInt("service.grpc.port")

    log.info(s"AI-Serving details: ${serverMetadataResponseFormat.write(Utils.getServerMetadata).toString()}")

    // start grpc server
    val grpcServer = new GrpcServer(executionContext, grpcPort)
    grpcServer.start()

    // start akka http server
    val bindingFuture = Http().newServerAt(host, httpPort).bindFlow(route)
    log.info(s"AI-Serving http server started, listening on http://${host}:${httpPort}/")

    grpcServer.blockUntilShutdown()
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}

object AIServerApp extends App {
  AIServer.start(args)
}
