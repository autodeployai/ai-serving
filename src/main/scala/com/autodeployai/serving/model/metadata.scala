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

import java.sql.Timestamp
import java.util.UUID

/**
 * Field info
 *
 * @param name   A unique name.
 * @param `type` Field type, main two kinds:
 *               - scalar types for PMML models: float, double, integer, string and so on.
 *               - tensor[element], map[key:value], and sequence[element], sequence[map[key:value]] for ONNX models.
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
                 shape: Option[Seq[Long]] = None,
                 values: Option[String] = None)

/**
 * Model version
 *
 * @param version
 */
case class ModelVersion(version: String) {
  def this() = this("1")
  def this(version: Int) = this(version.toString)
}

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
                     version: Option[ModelVersion] = Some(new ModelVersion),
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
                         latestVersion: ModelVersion = new ModelVersion(),
                         versions: Option[Seq[ModelInfo]] = None) {

  def withLatestVersion(): ModelMetadata = {
    val strVersion = latestVersion.version

    val nextVersion: String = try {
      val intVersion = Integer.parseInt(strVersion)
      Integer.toString(intVersion + 1)
    } catch {
      case _: NumberFormatException =>
        val idx = strVersion.lastIndexOf("_")
        if (idx != -1) {
          try {
            val numPart = Integer.parseInt(strVersion.substring(idx + 1))
            strVersion.substring(0, idx) + "_" + Integer.toString(numPart)
          } catch {
            case _: NumberFormatException => strVersion + "_1"
          }
        } else {
          strVersion + "_1"
        }
    }
    copy(latestVersion = ModelVersion(nextVersion))
  }

  def withUpdateAt(): ModelMetadata = copy(updateAt = new Timestamp(System.currentTimeMillis))

  def latestStrVersion: String =  latestVersion.version
}

object ModelMetadata {
  def apply(name: String): ModelMetadata = {
    val now: Timestamp = new Timestamp(System.currentTimeMillis)
    new ModelMetadata(UUID.randomUUID().toString, name, now, now)
  }
}

object DataType extends Enumeration {
  type DataType = Value
  val BOOL, UINT8, UINT16, UINT32, UINT64, INT8, INT16, INT32, INT64, FP16, FP32, FP64, BYTES, BF16 = Value
}

/**
 * @param name      The name of the tensor.
 * @param datatype  The data-type of the tensor elements as defined in Tensor Data Types.
 *               - Data Type	Size (bytes)
 *               - BOOL       1
 *               - UINT8      1
 *               - UINT16	    2
 *               - UINT32   	4
 *               - UINT64	    8
 *               - INT8	      1
 *               - INT16	    2
 *               - INT32	    4
 *               - INT64	    8
 *               - FP16	      2
 *               - FP32	      4
 *               - FP64	      8
 *               - BYTES	    Variable (max 232)
 *               - BF16	      2
 * @param shape   The shape of the tensor. Variable-size dimensions are specified as -1.
 */
case class MetadataTensor(name: String, datatype: String, shape: Seq[Long])

/**
 * @see https://github.com/kserve/kserve/blob/master/docs/predict-api/v2/required_api.md#model-metadata-response-json-object
 * @param name      The name of the model.
 * @param versions  The model versions that may be explicitly requested via the appropriate endpoint. Optional for servers that don’t support versions. Optional for models that don’t allow a version to be explicitly requested.
 * @param platform  The framework/backend for the model. See Platforms: https://github.com/kserve/kserve/blob/master/docs/predict-api/v2/required_api.md#platforms
 * @param inputs    The inputs required by the model.
 * @param outputs   The outputs produced by the model.
 */
case class ModelMetadataV2(name: String,
                           versions: Seq[String],
                           platform: String,
                           inputs: Seq[MetadataTensor],
                           outputs: Seq[MetadataTensor])

/**
 * Server Metadata
 *
 * @param name
 * @param version
 * @param extensions
 */
case class ServerMetadataResponse(name: String,
                                  version: String,
                                  extensions: Seq[String] = Seq.empty)


/**
 *
 * @param live
 */
case class ServerLiveResponse(live: Boolean)

/**
 *
 * @param ready
 */
case class ServerReadyResponse(ready: Boolean)

/**
 *
 * @param ready
 */
case class ModelReadyResponse(ready: Boolean)