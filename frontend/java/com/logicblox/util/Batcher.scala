package com.logicblox.util

import scala.concurrent.{Await, Promise, Future}
import scala.collection.mutable
import java.util.concurrent.{TimeUnit, ScheduledFuture, Executors, ScheduledExecutorService}
import com.google.common.util.concurrent.{FutureCallback, Futures}
import scala.util.{Failure, Success}

/**
 * A generic batcher of work.
 *
 * Clients add work to be done and wait for its completion via a future. Implementations extend the
 * execute method to define how accumulated work is to be executed.
 *
 */
abstract class Batcher[INPUT, OUTPUT] extends Runnable {

  /**
   * Execute the accumulated work.
   *
   * @param work a list of items that were accumulated to be executed at once.
   * @return a future to be completed when the work is done.
   */
  def execute(work: Seq[INPUT]): Future[Seq[OUTPUT]]

  /**
   * @return number of milliseconds to wait for items to accumulate before sending.
   */
  def window = 500

  /**
   * @return the maximum number of work items to process in a single round.
   */
  def maxSize = 1000

  /**
   * @return the number of times a work item can be retried. By default, never retry. If there is 
   * no max retries (None), retry indefinitely.
   */
  def maxRetries: Option[Int] = Some(0)

  /**
   * Add this work item to do.
   *
   * @param work some object representing a piece of work to be done.
   * @return a future that will be completed when the work is done.
   */
  def add(work: INPUT): Future[OUTPUT] = {
    val promise = Promise[OUTPUT]()
    queue.add(Record(work, promise))
    promise.future
  }

  /**
   * Start the batcher.
   */
  def start() = {
    synchronized {
      executorFuture = Some(
        executor.scheduleWithFixedDelay(this, 0, window, TimeUnit.MILLISECONDS)
      )
    }
  }

  /**
   * Shutdown the batcher.
   */
  def shutdown() = {
    synchronized {
      executorFuture match {
        case Some(f) => f.cancel(false)
        case _ =>
      }
      executor.shutdown()
    }
  }

  //
  // Private code
  //

  /**
   * Maps the work that was requested to the promise that should be completed when the work is done.
   *
   * @param work the input to be worked on.
   * @param promise the promise that should be completed when the work is done.
   * @param retries the number of times this input was attempted already.
   * @param nextTs the timestamp when this input is allowed to be attempted again (if it failed once
   * and was put in quarantine for a while.
   */
  case class Record(work: INPUT, promise: Promise[OUTPUT], retries: Int = 0, nextTs: Long = 0) {
    
    /**
     * Create a new Record object that represents the next retry of the same input. This should be
     * called if the execution of this Record failed.
     */
    def nextRetry() = {
      // 2 ^ retries, max 64, in ms
      val delta = Math.pow(2, Math.min(6, retries)).toLong * 1000
      Record(work, promise, retries + 1, System.currentTimeMillis() + delta)
    }
  }

  /**
   * The queue that accumulates work to do.
   */
  private val queue = new BatchQueue[Record]
  
  /**
   * Records in quarantine because of an exponential backoff policy. Records in this list failed at
   * least once, and are waiting some time before they can be retried.
   */
  private var quarantine = new mutable.ListBuffer[Record]()

  /**
   * The executor to periodically perform a batch.
   */
  private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1);

  /**
   * The future created when this batcher is scheduled in the executor (on start)
   */
  private var executorFuture: Option[ScheduledFuture[_]] = None

  /**
   * Execute a loop of the batcher.
   */
  def run() = {

    // check whether some records in quarantine can go back to the queue
    inspectQuarantine()
      
    while (! queue.isEmpty()) {
      
      // get records from the queue and process if any
      val records = queue.empty(maxSize)
      if (! records.isEmpty) {

        // what matters to execute is only the work, so extract from the records.
        val workList = records.map(_.work)
        try {

          // synchronously wait for the execution of the batch: 
          // we don't want concurrent calls to execute.
          val result = Await.result(execute(workList), scala.concurrent.duration.Duration.Inf)

          // the execution was successful, so complete all promisses.
          for ((record, index) <- records.zipWithIndex)
            record.promise.complete(Success(result(index)))

        } catch {

          // some exception thrown from execute (note that Await.result propagates exceptions
          // from inside the future).
          case t: Throwable =>

            // partition the records in the ones that we should retry and the ones that we should not
            val retryVsNot = records.partition(r => maxRetries match {
              // retry if there's no Max or if r was retried less than the max
              case None => true
              case Some(max) => r.retries < max
            })

            // retry the first partition
            if (! retryVsNot._1.isEmpty)
              quarantine ++= retryVsNot._1.map(_.nextRetry())

            // set the error on the second partition
            for (record <- retryVsNot._2)
              record.promise.complete(Failure(t))
        }
      }
      
      // avoid starvation of these records if we are in a burst
      inspectQuarantine()
    }
  }
  
  /**
   * Verify whether some records in quarantine can be added again to the queue.
   */
  private def inspectQuarantine() = {
    if (quarantine.size > 0) {
      val curr = System.currentTimeMillis()
      val removeVsNot = quarantine.partition(r => r.nextTs < curr)
      queue.add(removeVsNot._1.toList)
      quarantine = removeVsNot._2
    }
  }
}

/**
 * A very simple queue that offers only some thread-safe methods: add and empty (besides the 
 * side-effects free isEmpty).
 *
 * This queue is useful for batching: the batcher accumulates elements via 'add' and then atomically
 * gets a list of batched elements with 'empty'.
 *
 * @tparam T the type of elements in the queue.
 */
class BatchQueue[T] {

  /**
   * Maintain elements of type T.
   */
  private val queue = new mutable.ListBuffer[T]()

  /**
   * Add element to the queue.
   * 
   * @param element
   * @return
   */
  def add(element: T) = {
    synchronized {
      queue += element
    }
  }

  /**
   * Remove all elements from the queue.
   * 
   * @return the elements that were in the queue.
   */
  def empty(max: Int): List[T] = {
    synchronized {
      if (queue.size <= max) {
        val l = queue.toList
        queue.clear()
        l
      } else {
        val l = queue.toList
        queue.remove(0, max)
        l.take(max)
      }
    }
  }

  /**
   * Add a list of elements to the queue.
   *
   * @param list a list of elements to be added.
   * @return
   */
  def add(list: List[T]) = {
    synchronized {
      queue ++= list
    }
  }

  /**
   * @return whether the queue is empty.
   */
  def isEmpty() = queue.isEmpty
}
