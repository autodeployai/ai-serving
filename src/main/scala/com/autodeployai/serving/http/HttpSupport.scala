/*
 * Copyright (c) 2025, 2026 AutoDeployAI
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
package com.autodeployai.serving.http

import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.{Directive0, RouteResult}
import akka.http.scaladsl.server.RouteResult.{Complete, Rejected}
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.Directives.pass
import com.typesafe.config.ConfigFactory

trait HttpSupport {
  private val config = ConfigFactory.load()

  private val requestTimingEnabled: Boolean =
    if (config.hasPath("service.logging.request-timing-enabled")) config.getBoolean("service.logging.request-timing-enabled") else true

  private val requestTimingLogLevel: LogLevel = {
    val level = if (config.hasPath("service.logging.request-timing-level")) config.getString("service.logging.request-timing-level").toUpperCase else "INFO"
    level match {
      case "DEBUG" => Logging.DebugLevel
      case "WARNING" => Logging.WarningLevel
      case "ERROR" => Logging.ErrorLevel
      case _ => Logging.InfoLevel
    }
  }

  private def akkaResponseTimeLoggingFunction(loggingAdapter: LoggingAdapter,
                                              requestTimestamp: Long,
                                              level: LogLevel = Logging.InfoLevel)(req: HttpRequest)(res: RouteResult): Unit = {
    val entry = res match {
      case Complete(resp) =>
        val responseTimestamp: Long = System.nanoTime
        val elapsedTime: Long = (responseTimestamp - requestTimestamp) / 1000000
        val loggingString = s"""Logged request - "${req.method.value} ${req.uri.path} ${req.protocol.value}" ${resp.status} $elapsedTime(ms)"""
        LogEntry(loggingString, level)
      case Rejected(reason) =>
        LogEntry(s"Rejected reason: ${reason.mkString(", ")}", level)
    }
    entry.logTo(loggingAdapter)
  }

  private def printResponseTime(log: LoggingAdapter) = {
    val requestTimestamp = System.nanoTime
    akkaResponseTimeLoggingFunction(log, requestTimestamp, requestTimingLogLevel)_
  }

  val logResponseTime: Directive0 =
    if (requestTimingEnabled) DebuggingDirectives.logRequestResult(LoggingMagnet(printResponseTime)) else pass
}
