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

package com.autodeployai.serving.grpc

import com.autodeployai.serving.protobuf
import inference.GRPCInferenceServiceGrpc
import io.grpc.{Server, ServerBuilder}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext

class GrpcServer(executionContext: ExecutionContext, port: Int) {
  self =>

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  private[this] var server: Server = null

  def start(): Unit = {
    server = ServerBuilder.
      forPort(port).
      addService(protobuf.DeploymentServiceGrpc.bindService(new DeploymentServiceImpl, executionContext)).
      addService(GRPCInferenceServiceGrpc.bindService(new DeploymentServiceImplV2, executionContext)).
      build.
      start
    log.info("AI-Serving grpc server started, listening on " + port)
    sys.addShutdownHook {
      System.err.println("*** shutting down gRPC server since JVM is shutting down")
      self.stop()
      System.err.println("*** server shut down")
    }
  }

  def stop(): Unit = {
    if (server != null) {
      server.shutdown()
    }
  }

  def blockUntilShutdown(): Unit = {
    if (server != null) {
      server.awaitTermination()
    }
  }

}
