/*
 * Copyright (c) 2019-2024 AutoDeployAI
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
import com.autodeployai.serving.http.Endpoints
import com.autodeployai.serving.protobuf.GrpcServer
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

object AIServer extends Endpoints {
  val log: Logger = LoggerFactory.getLogger(this.getClass)

  val config: Config = ConfigFactory.load()

  implicit val system: ActorSystem = ActorSystem("AI-Serving", config)
  implicit val executionContext: MessageDispatcher = system.dispatchers.lookup("akka.ai-dispatcher")

  // Print all configurations of dispatcher.
  log.info(s"Configurations of ai-dispatcher: " +  s"${
    val result = new StringBuilder()
    val entries = executionContext.configurator.config.entrySet()
    val it = entries.iterator()
    while (it.hasNext) {
      val entry = it.next()
      result.append(s"\n${entry.getKey}: ${entry.getValue.render()}");
    }
    result}")

  lazy val route: Route = up() ~ validate() ~ modelsV1() ~ modelV1() ~ modelVersionV1()

  def start(args: Array[String]): Unit = {

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
