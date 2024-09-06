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

import java.nio.file.Paths
import protobuf.DeploymentServiceGrpc.DeploymentServiceBlockingStub
import protobuf.{DeploymentServiceGrpc, DeploymentServiceImpl}
import com.autodeployai.serving.deploy.ModelManager
import com.autodeployai.serving.utils.Utils
import io.grpc.inprocess.{InProcessChannelBuilder, InProcessServerBuilder}
import io.grpc.testing.GrpcCleanupRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.scalatest.Outcome

import scala.concurrent.ExecutionContext

abstract class BaseGrpcSpec extends BaseSpec {

  var grpcCleanup: GrpcCleanupRule = _

  val executionContext = ExecutionContext.global

  override protected def withFixture(test: NoArgTest): Outcome = {
    var outcome: Outcome = null
    val statementBody = () => {
      try {
        outcome = test()
      } finally {
        // Shared cleanup (run at end of each test)
        Utils.deleteDirectory(Paths.get(ModelManager.HOME_PATH))
      }
    }
    grpcCleanup = new GrpcCleanupRule()
    grpcCleanup(
      new Statement() {
        override def evaluate(): Unit = statementBody()
      },
      Description.createSuiteDescription("JUnit rule wrapper")
    ).evaluate()
    outcome
  }

  def blockingStub(): DeploymentServiceBlockingStub = {
    val serverName = InProcessServerBuilder.generateName
    grpcCleanup.register(InProcessServerBuilder
      .forName(serverName).directExecutor().addService(DeploymentServiceGrpc.bindService(new DeploymentServiceImpl, executionContext)).build().start())
    DeploymentServiceGrpc.blockingStub(grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build()))
  }
}
