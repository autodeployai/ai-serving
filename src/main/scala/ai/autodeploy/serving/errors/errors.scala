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

class BaseException(val message: String) extends Exception(message)

case class InvalidModelException(modelType: String, reason: String) extends
  BaseException(s"Invalid ${modelType} model received: ${reason}")

case class ModelNotFoundException(modelName: String, modelVersion: Option[Int] = None) extends
  BaseException(modelVersion.map(x => s"The version ${x} of '${modelName}' not found") getOrElse s"Model '${modelName}' not found")

case class ModelTypeNotSupportedException(modelType: Option[String]) extends
  BaseException(modelType.map(x => s"Model type '${x}' not supported") getOrElse s"Unknown model type")

case class ShapeMismatchException(actual: Array[Long], expected: Array[Long]) extends
  BaseException(s"Shape mismatch: ${expected} expected but ${actual} got")

case class MissingValueException(name: String, `type`: String, shape: Array[Long]) extends
  BaseException(s"Missing value for '${name}' in the input request")

case class UnknownDataTypeException(field: String) extends
  BaseException(s"Field '${field}' takes unknown data type")

case class OnnxRuntimeLibraryNotFoundError(reason: String) extends
  BaseException(s"Onnx Runtime initialization failed: ${reason}")

case class UnknownContentTypeException(contentType: Option[String] = None) extends
  BaseException(contentType.map(x => s"Prediction request takes unknown content type: ${x}") getOrElse s"The required header 'Content-Type' not found")