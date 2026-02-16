/*
 * Copyright (c) 2026 AutoDeployAI
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

package com.autodeployai.serving.deploy

import com.autodeployai.serving.model.{DataType, InferenceRequest, InferenceResponse}
import com.autodeployai.serving.utils.DataUtils
import org.slf4j.{Logger, LoggerFactory}

import java.nio.{ByteBuffer, ByteOrder, DoubleBuffer, FloatBuffer, IntBuffer, LongBuffer, ShortBuffer}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.concurrent.{ExecutionContext, Future, Promise}

trait BatchRequest[Request, Response] {
  def request: Request
  def promise: Promise[Response]
  def timestamp: Long
}

case class BatchRequestV2(
  request: InferenceRequest,
  promise: Promise[InferenceResponse],
  timestamp: Long = System.currentTimeMillis(),
) extends BatchRequest[InferenceRequest, InferenceResponse]

trait BatchProcessor[Request, Response] extends AutoCloseable {
  def predict(request: Request): Future[Response]

  def merge(requests: Array[Request]): Request

  def split(response: Response, recordCounts: Array[Int], recordIds: Array[Option[String]]): Array[Response]
}

class BatchProcessorV2(model: PredictModel,
                       maxBatchSize: Int,
                       maxBatchDelayMs: Long)(implicit ec: ExecutionContext) extends BatchProcessor[InferenceRequest, InferenceResponse] {

  val log: Logger = LoggerFactory.getLogger(this.getClass)
  private val queue = new ConcurrentLinkedQueue[BatchRequestV2]()
  private val enabled: Boolean = maxBatchSize > 1
  private val checkInterval: Long = Math.max(maxBatchDelayMs / 2, 1L)

  private val scheduler = if (enabled) {
    val s = Executors.newSingleThreadScheduledExecutor()
    s.scheduleAtFixedRate(
      () => {
        try {
          tryProcessBatch()
        } catch {
          case ex: Throwable =>
            log.error("Error in batch processor", ex)
        }
      },
      checkInterval,
      checkInterval,
      TimeUnit.MILLISECONDS
    )
    Some(s)
  } else None

  log.info(s"BatchProcessor for model ${model.modelName}:${model.modelVersion} initialized: max-batch-size=$maxBatchSize, max-batch-delay-ms=$maxBatchDelayMs")

  override def predict(request: InferenceRequest): Future[InferenceResponse] = {
    if (enabled) {
      val promise = Promise[InferenceResponse]()
      val batchRequest = BatchRequestV2(request=request, promise=promise)
      queue.offer(batchRequest)

      if (queue.size() >= maxBatchSize) {
        Future{
          processBatch()
        }
      }
      promise.future
    } else {
      Future(model.predict(request))
    }
  }

  private def tryProcessBatch(): Unit = {
    val timestamp = System.currentTimeMillis()

    if (!queue.isEmpty) {
      val oldestRequest = queue.peek()
      if (oldestRequest != null && (timestamp - oldestRequest.timestamp) >= maxBatchDelayMs) {
        processBatch()
      }
    }
  }

  private def processBatch(): Unit = {
    val builder = Array.newBuilder[BatchRequestV2]
    builder.sizeHint(maxBatchSize)
    var exit = false
    while (builder.length < maxBatchSize && !exit) {
      val item = queue.poll()
      if (item != null) {
        builder += item
      } else {
        exit = true
      }
    }

    val batch = builder.result()
    if (batch.length > 0) {
      try {
        val requests = batch.map(_.request)
        val mergedRequest = merge(requests)

        val startTime = System.currentTimeMillis()
        val batchResponse = model.predict(mergedRequest)
        log.info(s"Batched ${batch.length} requests elapsed time: ${System.currentTimeMillis() - startTime}")

        val recordCounts = requests.map(req => {
          if (req.inputs.nonEmpty) {
            req.inputs.head.shape.headOption.map(_.toInt).getOrElse(1)
          } else 1
        })
        val recordIds = requests.map(_.id)
        val responses = split(batchResponse, recordCounts, recordIds)

        batch.zip(responses).foreach {
          case (req, res) => req.promise.success(res)
        }
      } catch {
        case ex: Throwable =>
          log.error(s"Error processing batch for model ${model.modelName}:${model.modelVersion}ms", ex)
          batch.foreach(_.promise.failure(ex))
      }
    }
  }

  override def merge(requests: Array[InferenceRequest]): InferenceRequest = {
    val length = requests.length
    if (length == 1) {
      return requests.head
    }

    val first = requests.head
    val mergedInputs = first.inputs.map(input => {
      val inputs = requests.flatMap(req => req.inputs.find(_.name == input.name))
      val dataType = DataType.withName(inputs.head.datatype)
      val sizeOf = DataUtils.sizeof(dataType)
      val length = inputs.map(x => x.data match {
        case arr: Array[_]   =>
          arr.length
        case buf: ByteBuffer =>
          buf.capacity() / sizeOf
      }).sum

      val mergedData = dataType match {
        case DataType.FP32 =>
          val output = FloatBuffer.allocate(length)
          inputs.foreach(x => x.data match {
            case arr: Array[Float] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf.asFloatBuffer())
            case buf: FloatBuffer  => output.put(buf)
          })
          output.array()
        case DataType.FP64 =>
          val output = DoubleBuffer.allocate(length)
          inputs.foreach(x => x.data match {
            case arr: Array[Double] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf.asDoubleBuffer())
            case buf: DoubleBuffer  => output.put(buf)
          })
          output.array()
        case DataType.INT64 | DataType.UINT64 =>
          val output = LongBuffer.allocate(length)
          inputs.foreach(x => x.data match {
            case arr: Array[Long] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf.asLongBuffer())
            case buf: LongBuffer  => output.put(buf)
          })
          output.array()
        case DataType.INT32 | DataType.UINT32 =>
          val output = IntBuffer.allocate(length)
          inputs.foreach(x => x.data match {
            case arr: Array[Int] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf.asIntBuffer())
            case buf: IntBuffer  => output.put(buf)
          })
          output.array()
        case DataType.INT16 | DataType.UINT16 | DataType.FP16 | DataType.BF16  =>
          val output = ShortBuffer.allocate(length)
          inputs.foreach(x => x.data match {
            case arr: Array[Short] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf.asShortBuffer())
            case buf: ShortBuffer  => output.put(buf)
          })
          output.array()
        case DataType.INT8 | DataType.UINT8   =>
          val output = ByteBuffer.allocate(length).order(ByteOrder.nativeOrder)
          inputs.foreach(x => x.data match {
            case arr: Array[Byte] => output.put(arr)
            case buf: ByteBuffer   => output.put(buf)
          })
          output.array()
        case DataType.BOOL  =>
          val boolArrays = inputs.map(x => x.data match {
            case arr: Array[Boolean] =>
              arr
            case buf: ByteBuffer =>
              DataUtils.convertToBooleanArray(buf.array())
          })
          val output = new Array[String](boolArrays.map(_.length).sum)
          var i = 0
          var offset = 0
          while (i < boolArrays.length) {
            val arr = boolArrays(0)
            System.arraycopy(arr, 0, output, offset, arr.length)
            i += 1
            offset += arr.length
          }
          output
        case DataType.BYTES =>
          val strArrays = inputs.map(x => x.data match {
            case arr: Array[String] =>
              arr
            case buf: ByteBuffer =>
              DataUtils.readBinaryString(buf)
          } )
          val output = new Array[String](strArrays.map(_.length).sum)
          var i = 0
          var offset = 0
          while (i < strArrays.length) {
            val arr = strArrays(0)
            System.arraycopy(arr, 0, output, offset, arr.length)
            i += 1
            offset += arr.length
          }
          output
      }

      // Shape of batched data
      val allShape = requests.flatMap(req => req.inputs.find(_.name == input.name).map(_.shape))
      val newShape = allShape.map(x =>x.head).sum +: allShape.head.tail

      input.copy(data = mergedData, shape = newShape)
    })

    first.copy(inputs = mergedInputs)
  }

  override def split(response: InferenceResponse, recordCounts: Array[Int], recordIds: Array[Option[String]]): Array[InferenceResponse] = {
    if (recordCounts.length == 1) {
      Array(response)
    } else {
      val numRecords = recordCounts.sum
      val splitOutputs = response.outputs.map { output =>
        output.data match {
          case arr: Array[_] =>
            val elementsPerRecord = arr.length / numRecords

            var offset = 0
            recordCounts.map { count =>
              val numElements = count * elementsPerRecord
              val subData = arr.slice(offset, offset + numElements)
              offset += numElements

              val newShape = count.toLong +: output.shape.tail
              output.copy(data = subData, shape = newShape)
            }
          case buf: ByteBuffer =>
            val elementsPerRecord = buf.capacity() / numRecords

            var offset = 0
            recordCounts.map { count =>
              val numElements = count * elementsPerRecord
              val subData = buf.slice(offset, numElements)
              offset += numElements

              val newShape = count.toLong +: output.shape.tail
              output.copy(data = subData, shape = newShape)
            }
          case _ =>
            Array.fill(recordCounts.length)(output)
        }
      }

      recordCounts.zipWithIndex.map(x =>
        response.copy(
          id = recordIds(x._2),
          outputs = splitOutputs.map(_(x._2))
        )
      )
    }
  }

  override def close(): Unit = {
    scheduler.foreach(s =>{
      s.shutdown()
      try {
        if (s.awaitTermination(5, TimeUnit.SECONDS)) {
          s.shutdownNow()
        }
      } catch {
        case _: InterruptedException =>
          s.shutdownNow()
      }
    })

    if (!queue.isEmpty) {
      processBatch()
    }
  }
}
