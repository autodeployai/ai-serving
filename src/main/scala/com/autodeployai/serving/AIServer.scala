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

package com.autodeployai.serving

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import com.autodeployai.serving.http.Endpoints
import com.autodeployai.serving.protobuf.GrpcServer
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

object AIServer extends Endpoints {
  val log = LoggerFactory.getLogger(this.getClass)

  val config = ConfigFactory.load()

  implicit val system = ActorSystem("AI-Serving", config)
  implicit val executionContext = system.dispatchers.lookup("akka.ai-predicting-dispatcher")
  log.info(s"Predicting thread pool size: ${executionContext.configurator.config.getInt("thread-pool-executor.fixed-pool-size")}")

  lazy val route = up() ~ validate() ~ modelsV1() ~ modelV1() ~ modelVersionV1()

  def start(args: Array[String]) = {

    val host = config.getString("service.http.interface")
    val httpPort = config.getInt("service.http.port")
    val grpcPort = config.getInt("service.grpc.port")

    // start grpc server
    val grpcServer = new GrpcServer(executionContext, grpcPort)
    grpcServer.start()

    // start akka http server
    val bindingFuture = Http().bindAndHandle(route, host, httpPort)
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
