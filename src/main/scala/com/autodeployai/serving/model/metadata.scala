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

package com.autodeployai.serving.model

import java.sql.Timestamp
import java.util.UUID

/**
 * Field info
 *
 * @param name   A unique name.
 * @param `type` Field type, main two kinds:
 *               - scalar types for PMML models: float, double, integer, string and so on.
 *               - tensor, map, and list for ONNX models.
 * @param optype Determines which operations are defined on the values:
 *               - categorical
 *               - ordinal
 *               - continuous
 * @param shape  Field shape dimensions, mainly used for the tensor field, None for others.
 * @param values A string describes valid values for this field.
 */
case class Field(name: String,
                 `type`: String,
                 optype: Option[String] = None,
                 shape: Option[List[Long]] = None,
                 values: Option[String] = None)

/**
 * Model info
 *
 * @param `type`        Model type.
 * @param serialization Model serialization type.
 * @param runtime       The runtime library to handle such model.
 * @param inputs        A list of inputs involved to predict this model.
 * @param targets       A list of targets.
 * @param outputs       A list of outputs could be produced by this model.
 * @param redundancies  A list of redundancy fields not picked up by this model.
 * @param algorithm     Model algorithm.
 * @param functionName  Mining function: regression, classification, clustering, or associationRules.
 * @param description   Model description.
 * @param version       Model version.
 * @param formatVersion The version of model serialization standard.
 * @param hash          The MD5 hash string of this model file.
 * @param size          The size of this model file in bytes.
 * @param createdAt     Model creation timestamp.
 * @param app           The application that generated this model.
 * @param appVersion    The version of the application.
 * @param copyright     Model copyright.
 * @param source        Original model source.
 */
case class ModelInfo(`type`: String,
                     serialization: String,
                     runtime: String,
                     inputs: Option[Seq[Field]] = None,
                     targets: Option[Seq[Field]] = None,
                     outputs: Option[Seq[Field]] = None,
                     redundancies: Option[Seq[Field]] = None,
                     algorithm: Option[String] = None,
                     functionName: Option[String] = None,
                     description: Option[String] = None,
                     version: Option[Int] = Some(1),
                     formatVersion: Option[String] = None,
                     hash: Option[String] = None,
                     size: Option[Long] = None,
                     createdAt: Option[Timestamp] = Some(new Timestamp(System.currentTimeMillis())),
                     app: Option[String] = None,
                     appVersion: Option[String] = None,
                     copyright: Option[String] = None,
                     source: Option[String] = None)


/**
 * Model metadata with versions.
 *
 * @param id            Model ID.
 * @param name          A unique model name.
 * @param createdAt     Model creation timestamp.
 * @param updateAt      Model last updated timestamp.
 * @param latestVersion The latest version number.
 * @param versions      Model version(s).
 */
case class ModelMetadata(id: String,
                         name: String,
                         createdAt: Timestamp,
                         updateAt: Timestamp,
                         latestVersion: Int = 1,
                         versions: Option[Seq[ModelInfo]] = None) {

  def withLatestVersion(latestVersion: Int = latestVersion + 1): ModelMetadata = copy(latestVersion = latestVersion)

  def withUpdateAt(): ModelMetadata = copy(updateAt = new Timestamp(System.currentTimeMillis))
}

object ModelMetadata {
  def apply(name: String): ModelMetadata = {
    val now: Timestamp = new Timestamp(System.currentTimeMillis)
    new ModelMetadata(UUID.randomUUID().toString, name, now, now)
  }
}
