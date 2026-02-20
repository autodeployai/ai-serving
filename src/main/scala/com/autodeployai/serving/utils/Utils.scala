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

package com.autodeployai.serving.utils

import java.io.{BufferedInputStream, IOException}
import java.net.URLConnection
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.Comparator
import akka.http.scaladsl.model.ContentType
import com.autodeployai.serving.deploy.InferenceService
import com.autodeployai.serving.model.ServerMetadataResponse
import com.google.protobuf.ByteString
import org.slf4j.{Logger, LoggerFactory}

import java.util.jar.Attributes.Name
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object Utils {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  def write(path: Path, bytes: ByteString): Path = {
    using(Files.newOutputStream(path)) { os =>
      bytes.writeTo(os)
      path
    }
  }

  def inferModelType(path: Path, `type`: Option[String] = None, contentType: Option[ContentType] = None): String = `type` match {
    case Some(value) if value.nonEmpty => value.toUpperCase
    case _                             => {
      contentType.flatMap(x => inferModelType(x.mediaType.value)) getOrElse {
        probeContentType(path).flatMap(inferModelType).getOrElse("")
      }
    }
  }

  def inferModelType(mime: String): Option[String] = Option(mime match {
    case "application/xml" | "text/xml"                                                            => "PMML"
    case "application/octet-stream" | "application/vnd.google.protobuf" | "application/x-protobuf" => "ONNX"
    case "application/json"                                                                        => "PFA"
    case _                                                                                         => null
  })


  def probeContentType(path: Path): Option[String] = {
    val result = Try(Files.probeContentType(path)) match {
      case Success(v) => Option(v)
      case Failure(_) => None
    }

    result orElse using(new BufferedInputStream(Files.newInputStream(path))) { is =>
      Try(URLConnection.guessContentTypeFromStream(is)) match {
        case Success(v) => Option(v)
        case Failure(_) => None
      }
    }
  }

  def toOption(s: String): Option[String] = {
    Option(s) filterNot {
      _.isEmpty
    }
  }

  def toOption[T](seq: Seq[T]): Option[Seq[T]] = {
    Option(seq) filterNot {
      _.isEmpty
    }
  }

  def toOption[T, T1](map: Map[T, T1]): Option[Map[T, T1]] = {
    Option(map) filterNot {
      _.isEmpty
    }
  }

  def nonEmpty(s: String): Boolean = s != null && s.nonEmpty

  def deleteDirectory(path: Path): Boolean = {
    if (Files.exists(path)) {
      Files.walk(path).sorted(Comparator.reverseOrder()).forEach(x => Files.delete(x))
    }
    true
  }

  def tempFilePath(prefix: String, suffix: String): Path = {
    Files.createTempFile(prefix, suffix)
  }

  def tempFilePath(): Path = {
    val prefix = s"ai-serving-uploaded-${System.currentTimeMillis()}-"
    tempFilePath(prefix, ".tmp")
  }

  def md5Hash(path: Path): Option[String] = Try {
    val buffer = new Array[Byte](8192)
    val md5 = MessageDigest.getInstance("MD5")

    using(new DigestInputStream(Files.newInputStream(path), md5)) { dis =>
      while (dis.read(buffer) != -1) {}
    }
    md5.digest.map("%02x".format(_)).mkString
  } match {
    case Success(value) => Some(value)
    case Failure(_)     => None
  }

  def fileSize(path: Path): Option[Long] = Try(Files.size(path)) match {
    case Success(value) => Some(value)
    case Failure(_)     => None
  }

  def safeDelete(path: Path): Unit = {
    try {
      if (path != null) {
        Files.delete(path)
      }
    } catch {
      case ex: Exception => {
        log.warn(s"Failed to delete file: ${ex.getMessage}")
      }
    }
  }

  def using[A <: AutoCloseable, B](resource: A)(work: A => B): B = {
    try {
      work(resource)
    } finally {
      safeClose(resource)
    }
  }

  def safeClose(resource: AutoCloseable): Unit = {
    try {
      if (resource != null) {
        resource.close()
      }
    } catch {
      case e: Exception =>
        log.warn(s"Failed to close resource: ${e.getMessage}")
    }
  }

  def elementCount(shape: Array[Long]): Long = {
    // filter the dynamic axes that take -1
    shape.filter(x => x != -1).foldLeft(1L)((x, y) => x * y)
  }

  def elementCount(shape: Seq[Long]): Long = {
    // filter the dynamic axes that take -1
    shape.filter(x => x != -1).foldLeft(1L)((x, y) => x * y)
  }

  def shapeOfValue(value: Any): Array[Long] = {
    val result: ArrayBuffer[Long] = ArrayBuffer.empty
    dimensionOfValue(value, result)
    result.toArray
  }

  def isDynamicShape(shape: Array[Long]): Boolean = shape.contains(-1L)

  def supportDynamicBatch(shapes: Seq[Option[Seq[Long]]]): Boolean = {
    shapes.forall(x => x.exists(shape =>
      shape.nonEmpty && shape.head == -1L && !shape.tail.contains(-1L)))
  }

  @tailrec
  private def dimensionOfValue(value: Any, result: ArrayBuffer[Long]): Unit = value match {
    case seq: Seq[_]     => {
      val size = seq.size
      result += size
      if (size > 0) {
        dimensionOfValue(seq.head, result)
      }
    }
    case array: Array[_] => {
      val size = array.length
      result += size
      if (size > 0) {
        dimensionOfValue(array(0), result)
      }
    }
    case _               =>
  }

  private def getComponentClass(arr: Array[_]): Class[_] = {
    getComponentClass(arr.getClass)
  }

  @tailrec
  private def getComponentClass(clazz: Class[_]): Class[_] = {
    if (clazz.isArray) {
      getComponentClass(clazz.getComponentType)
    } else clazz
  }

  def flatten(seq: Seq[Any]): Seq[Any] = {
    val result = IndexedSeq.newBuilder[Any]
    flattenSeq(seq, result)
    result.result()
  }

  private def flattenSeq(seq: Seq[Any], output: mutable.Builder[Any, IndexedSeq[Any]]): Unit = {
    seq.foreach {
      case s: Seq[_] => flattenSeq(s, output)
      case a: Array[_] => flattenSeq(a.toSeq, output)
      case x: Any => output += x
    }
  }

  def flatten(arr: Array[_]): Array[_] = {
    val clazz = getComponentClass(arr)
    val output = Array.newBuilder[Any](ClassTag(clazz))
    output.sizeHint(elementCount(shapeOfValue(arr)).toInt)
    flattenArray(arr, output)
    output.result()
  }

  private def flattenArray(arr: Array[_], output: mutable.ArrayBuilder[Any]): Unit = {
    if (arr.getClass.getComponentType.isArray) {
      var i = 0
      while (i < arr.length) {
        flattenArray(arr(i).asInstanceOf[Array[_]], output)
        i += 1
      }
    } else {
      output ++= arr.toSeq
    }
  }

  def getNumCores: Int = {
    Runtime.getRuntime.availableProcessors()
  }

  def dataTypeV1ToV2(dataType: String): String = dataType match {
    case "string"         =>
      "BYTES"
    case "integer"        =>
      "INT32"
    case "float"          =>
      "FP32"
    case "double" | "real"=>
      "FP64"
    case "boolean"        =>
      "BOOL"
    case "date" | "time" | "dateTime" =>
      "BYTES"
    case "dateDaysSince[0]" | "dateDaysSince[1960]" | "dateDaysSince[1970]" | "dateDaysSince[1980]" =>
      "INT64"
    case "timeSeconds" =>
      "INT32"
    case "dateTimeSecondsSince[0]" | "dateTimeSecondsSince[1960]" | "dateTimeSecondsSince[1970]" | "dateTimeSecondsSince[1980]" =>
      "INT64"
    case _                =>
      val unknown = "UNKNOWN"
      if (dataType.startsWith("tensor")) {
        val idxLeft = dataType.indexOf('[')
        val idxRight = dataType.indexOf(']')
        if (idxLeft != -1 && idxRight != -1) {
          val tensorType = dataType.substring(idxLeft + 1, idxRight)
          tensorType match {
            case "float"    => "FP32"
            case "double"   => "FP64"
            case "STRING"   => "BYTES"
            case "float16"  => "FP16"
            case "BFLOAT16" => "BF16"
            case _          => tensorType.toUpperCase()
          }
        } else unknown
      } else if (dataType.startsWith("map")) {
        "BYTES"
      } else if (dataType.startsWith("seq")) {
        "BYTES"
      } else unknown
  }

  def getServerMetadata: ServerMetadataResponse = {
    var name = "ai-serving"
    var version = "NA"
    var pmmlVersion = "NA"
    var onnxruntimeVersion = "NA"
    try {
      val pmml4sName = new Name("pmml4s-Version")
      val resources =  this.getClass.getClassLoader.getResources("META-INF/MANIFEST.MF")
      var continue = true
      while (resources.hasMoreElements && continue) {
        val manifest = new java.util.jar.Manifest(resources.nextElement().openStream())
        val attr = manifest.getMainAttributes
        if (attr.containsKey(pmml4sName)) {
          name = attr.getValue("Implementation-Title")
          version = attr.getValue("Implementation-Version")
          pmmlVersion = attr.getValue("pmml4s-Version")
          onnxruntimeVersion = attr.getValue("onnxruntime-Version")
          continue = false
        }
      }
    } catch {
      case _: IOException =>
    }

    ServerMetadataResponse(
      name = name,
      version = version
    )
  }

  def readyJson(ready: Boolean): String = {
    val flag = if (ready) "true" else "false"
    s"""{"ready":$flag}"""
  }
}
