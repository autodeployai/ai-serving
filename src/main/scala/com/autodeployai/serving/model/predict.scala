/*
 * Copyright (c) 2019-2025 AutoDeployAI
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

package com.autodeployai.serving.model

/**
 * Takes more than one records, there are two formats supported:
 *
 * - `records` : list like [{column -> value}, … , {column -> value}]
 * - `split` : dict like {columns -> [columns], data -> [values]}
 */
case class RecordSpec(records: Option[Seq[Map[String, Any]]] = None,
                      columns: Option[Seq[String]] = None,
                      data: Option[Seq[Seq[Any]]] = None) {
  require(records.isDefined || (columns.isDefined && data.isDefined),
    "either records is present or both columns and data are present together.")
}

/**
 * Request to predict
 *
 * @param X      Input payload
 * @param filter Output filters to specify which output fields need to be returned.
 *               If the list is empty, all outputs will be included.
 */
case class PredictRequest(X: RecordSpec, filter: Option[Seq[String]] = None)

/**
 * Response for predicting request on successful run
 *
 * @param result Output result
 */
case class PredictResponse(result: RecordSpec)

/**
 * A request input contains a tensor with specified shape
 * @param name
 * @param shape
 * @param datatype
 * @param parameters
 * @param data
 */
case class RequestInput(name: String,
                        shape: Array[Long],
                        datatype: String,
                        parameters: Option[Map[String, Any]],
                        data: Any)

/**
 * Contains a request output expected by client
 * @param name
 * @param parameters
 */
case class RequestOutput(name: String,
                         parameters: Option[Map[String, Any]])

/**
 * An inference request that contains all required inputs.
 * @param id
 * @param parameters
 * @param inputs
 * @param outputs
 */
case class InferenceRequest(id: Option[String],
                            parameters: Option[Map[String, Any]],
                            inputs: Seq[RequestInput],
                            outputs: Option[Seq[RequestOutput]])

/**
 * A response output contains a tensor with specified shape
 * @param name
 * @param shape
 * @param datatype
 * @param parameters
 * @param data  An array of values or a single scalar
 */
case class ResponseOutput(name: String,
                          shape: Array[Long],
                          datatype: String,
                          parameters: Option[Map[String, Any]],
                          data: Any)

/**
 * An inference response with all expected outputs
 * @param model_name
 * @param model_version
 * @param id
 * @param parameters
 * @param outputs
 */
case class InferenceResponse(model_name: String="",
                             model_version: Option[String]=None,
                             id: Option[String],
                             parameters: Option[Map[String, Any]],
                             outputs: Seq[ResponseOutput]) {

  def withModelSpec(name: String, version: Option[String]): InferenceResponse =
    InferenceResponse(model_name = name,
      model_version = version,
      id = this.id,
      parameters = this.parameters,
      outputs = this.outputs)
}