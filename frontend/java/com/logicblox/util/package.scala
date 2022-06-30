package com.logicblox

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture

import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

package object util {

  /**
   * This import allows the implicits to be applied package-wide.
   */
  import scala.language.implicitConversions
  
  /**
   * An implicit function that transforms a guava listenable future into a scala future.
   *
   * @param future
   * @tparam T
   * @return
   */
  implicit def guavaToScala[T](future: ListenableFuture[T]): Future[T] = {
    val promise = Promise[T]()

    Futures.addCallback(future,
      new FutureCallback[T] {
        def onSuccess(result: T) = promise success result
        def onFailure(throwable: Throwable) = promise failure throwable
      },
      MoreExecutors.directExecutor()
    )

    promise.future
  }

  /**
   * An implicit function that transforms a scala future into a guava listenable future.
   *
   * @param future
   * @tparam T
   * @return
   */
  implicit def scalaToGuava[T](future: Future[T]): ListenableFuture[T] = {
    val promise = SettableFuture.create[T]()

    future onComplete {
      case Success(result) => promise.set(result)
      case Failure(throwable: Throwable) => promise.setException(throwable)
    }

    promise
  }
}
