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

package ai.autodeploy.serving.utils

import java.io.BufferedInputStream
import java.net.URLConnection
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.Comparator

import akka.http.scaladsl.model.ContentType
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable.{ArrayBuffer, ArrayBuilder}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object Utils {

  val log = LoggerFactory.getLogger(this.getClass)

  def getFileExtension(filename: String, default: String = "") = {
    val dotIdx = filename.lastIndexOf('.')
    if (dotIdx == -1) {
      default
    } else {
      filename.substring(dotIdx)
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

  def nonEmpty(s: String): Boolean = s != null && s.nonEmpty

  def deleteDirectory(path: Path): Boolean = {
    if (Files.exists(path)) {
      Files.walk(path).sorted(Comparator.reverseOrder()).forEach(x => Files.delete(x))
    }
    true
  }

  def tempPath(prefix: String = "ai-serving-uploaded-", suffix: String = ".tmp"): Path = {
    Files.createTempFile(prefix, suffix)
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
      case e: Exception => {
        log.warn(s"Failed to close resource: ${e.getMessage}")
      }
    }
  }

  def elementCount(shape: Array[Long]): Long = {
    // filter the dynamic axes that take -1
    shape.filter(x => x != -1).foldLeft(1L)((x, y) => x * y)
  }

  def shapeOfValue(value: Any): Array[Long] = {
    val result: ArrayBuffer[Long] = ArrayBuffer.empty
    dimensionOfValue(value, result)
    result.toArray
  }

  def isDynamicShape(shape: Array[Long]): Boolean = shape.contains(-1L)

  @tailrec
  def dimensionOfValue(value: Any, result: ArrayBuffer[Long]): Unit = value match {
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

  def getComponentClass(arr: Array[_]): Class[_] = {
    getComponentClass(arr.getClass)
  }

  @tailrec
  def getComponentClass(clazz: Class[_]): Class[_] = {
    if (clazz.isArray) {
      getComponentClass(clazz.getComponentType)
    } else clazz
  }

  def flatten(arr: Array[_]): Any = {
    val clazz = getComponentClass(arr)
    val output = Array.newBuilder[Any](ClassTag(clazz))
    output.sizeHint(elementCount(shapeOfValue(arr)).toInt)
    flattenArray(arr, output)
    output.result()
  }

  def flattenArray(arr: Array[_], output: ArrayBuilder[Any]): Unit = {
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

}
