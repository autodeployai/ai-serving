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

package com.autodeployai.serving.errors

import com.autodeployai.serving.utils.Utils

import java.util

class BaseException(val message: String) extends Exception(message)

case class InvalidModelException(modelType: String, reason: String) extends
  BaseException(s"Invalid ${modelType} model received: ${reason}")

case class ModelNotFoundException(modelName: String, modelVersion: Option[String] = None) extends
  BaseException(modelVersion.map(x => s"The version ${x} of '${modelName}' not found") getOrElse s"Model '${modelName}' not found")

case class ModelTypeNotSupportedException(modelType: Option[String]) extends
  BaseException(modelType.map(x => s"Model type '${x}' not supported") getOrElse s"Unknown model type")

case class ShapeMismatchException(actual: Array[Long], expected: Array[Long]) extends
  BaseException(s"Shape mismatch: ${util.Arrays.toString(expected)} expected but ${util.Arrays.toString(actual)} got")

case class MissingValueException(name: String) extends
  BaseException(s"Missing value for '${name}' in the input request")

case class UnknownDataTypeException(field: String) extends
  BaseException(s"Field '${field}' takes unknown data type")

case class OnnxRuntimeLibraryNotFoundError(reason: String) extends
  BaseException(s"Onnx Runtime initialization failed: ${reason}")

case class UnknownContentTypeException(contentType: Option[String] = None) extends
  BaseException(contentType.map(x => s"Prediction request takes unknown content type: ${x}") getOrElse s"The required header 'Content-Type' not found")

case class InputNotSupportedException(input: String, typ: String) extends
  BaseException(s"The input field '${input}' with value ${typ} not supported")

case class OutputNotSupportedException(input: String, typ: String) extends
  BaseException(s"The output field '${input}' with value ${typ} not supported")

case class InvalidInputException(field: String, shape: Seq[Long], elementCount: Int) extends
  BaseException(s"The input ${field} with shape $shape ${Utils.elementCount(shape)} expected but ${elementCount} got")

case class InvalidInputDataException(field: String) extends
  BaseException(s"The input ${field} tensor data expected but scalar or map got")

case class InferTimeoutException(modelName: String, modelVersion: Option[String], timeout: Long) extends
  BaseException(s"Request to $modelName:${modelVersion.getOrElse("latest")} exceeded timeout of ${timeout} milliseconds")