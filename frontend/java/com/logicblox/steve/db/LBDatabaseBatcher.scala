package com.logicblox.steve.db

import com.logicblox.util.Batcher
import com.logicblox.steve.protocol.Database.Request
import com.logicblox.steve.protocol.Database.Response
import com.logicblox.steve.protocol.Database.RequestEnvelope
import scala.concurrent.Future
import scala.collection.JavaConversions._
import com.logicblox.bloxweb.client.ProtobufServiceClient
import com.logicblox.bloxweb.ProtoBufExchange
import com.logicblox.steve.protocol.Database.ResponseEnvelope
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.FutureCallback
import scala.concurrent.Promise
import com.google.common.util.concurrent.Futures
import scala.concurrent.ExecutionContext
import scala.collection.mutable
import com.google.common.util.concurrent.SettableFuture
import scala.util.Failure
import scala.util.Success
import com.timgroup.statsd.StatsDClient
import com.timgroup.statsd.NonBlockingStatsDClient

import com.logicblox.util._

/**
 * An implementation of a Batcher that batches LBDatabase requests, and return responses.
 */
class LBDatabaseBatcher(client: ProtobufServiceClient, readOnly: Boolean = false) extends Batcher[Request, Response] {

  /**
   * An implicit context to execute future combinators asynchronously.
   */
  implicit val context = ExecutionContext.fromExecutor(new scala.concurrent.forkjoin.ForkJoinPool)

  /**
   * Statsd client to monitor batch related metrics.
   */
  val statsd = new NonBlockingStatsDClient("lb.steve.internal", "localhost", 8125)
  val metric = if readOnly "batcher.read.size" else "batcher.write.size"
    
  /**
   * Do not impose a limit in the number of requests per batch.
   */
  override def maxSize = Int.MaxValue
  
  /**
   * The implementation of batching.
   */
  def execute(work: Seq[Request]): Future[Seq[Response]] = {
    statsd.recordExecutionTime(metric, work.size)
    
    // add all requests to the envelope
    val builder = RequestEnvelope.newBuilder().addAllRequest(work);

    // use the protobuf client to post the envelope as a request, and hook 
    // a function to process the response
    val exch = new ProtoBufExchange(builder.build(), ResponseEnvelope.newBuilder())
    exch.setReadOnly(readOnly)

    client.postMessage(exch)
    .map(exchange => {
      exchange.getResponseMessage().asInstanceOf[ResponseEnvelope].getResponseList()
    })
  }
  
  /**
   * A convenience method for Java clients. This just calls Batcher.add, but uses an implicit
   * conversion to get a ListenableFuture back.
   */
  def addRequest(work: Request): ListenableFuture[Response] = add(work)  
}
