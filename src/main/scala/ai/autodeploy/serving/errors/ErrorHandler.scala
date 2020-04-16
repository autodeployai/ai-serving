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

package ai.autodeploy.serving.errors

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server._
import org.slf4j.LoggerFactory

object ErrorHandler extends ErrorJsonSupport {

  val log = LoggerFactory.getLogger(this.getClass)

  def defaultExceptionHandler: ExceptionHandler = ExceptionHandler {
    case bex: BaseException => {
      val code = bex match {
        case _: ModelNotFoundException          => NotFound
        case _: OnnxRuntimeLibraryNotFoundError => InternalServerError
        case _                                  => BadRequest
      }
      val error = bex.message
      log.warn(error)
      complete(code, Error(error))
    }
    case ex: Throwable      => {
      val error = ex.getMessage
      log.error(error)
      complete(InternalServerError, Error(error))
    }
    case _                  => {
      val error = "Unknown error occurred"
      log.error(error)
      complete(InternalServerError, Error(error))
    }
  }

  def defaultRejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handle {
      case RequestEntityExpectedRejection => {
        val error = "No inputs, no actions"
        log.warn(error)
        complete(BadRequest, Error(error))
      }
    }
    .handle {
      case MissingFormFieldRejection(fieldName) => {
        val error = s"The form field '${fieldName}' not found"
        log.warn(error)
        complete(BadRequest, Error(error))
      }
    }
    .handle {
      case MissingHeaderRejection(headerName) => {
        val error = s"The required header '${headerName}' not found"
        log.warn(error)
        complete(BadRequest, Error(error))
      }
    }
    .result()

}
