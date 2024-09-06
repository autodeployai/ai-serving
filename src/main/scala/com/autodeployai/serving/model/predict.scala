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
