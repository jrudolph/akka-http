/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.util.concurrent.ConcurrentHashMap

import akka.stream.Attributes.Attribute
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, Inlet, Outlet, Shape }

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

/** A side-channel for transporting initialization values between stages */
private[akka] trait StageValueMap extends Attribute {
  def get[T](key: StageValueKey[T]): Future[T]
  def put[T](key: StageValueKey[T], value: T): Unit
}

private[akka] abstract class StageValueKey[T](val name: String)

private[akka] object StageValueMap {
  def apply(): StageValueMap =
    new StageValueMap {
      // A slot can be in several states:
      //  * uninitialized (entry is empty)
      //  * waiting (entry is a Waiting(promise) to be completed when the value arrives)
      //  * set (entry is a the value itself)

      val store = new ConcurrentHashMap[StageValueKey[_], AnyRef]()

      @tailrec
      def get[T](key: StageValueKey[T]): Future[T] =
        if (store.contains(key))
          store.get(key) match {
            case Waiting(p: Promise[T]) ⇒ p.future
            case x: T                   ⇒ Future.successful(x)
          }
        else {
          val p = Promise[AnyRef]()
          val res = store.putIfAbsent(key, Waiting(p))

          if (res eq null) p.future.asInstanceOf[Future[T]] // was still empty
          else get(key) // retry, reader or writer got here first
        }

      @tailrec
      def put[T](key: StageValueKey[T], value: T): Unit =
        if (store.contains(key))
          store.get(key) match {
            case Waiting(p: Promise[T]) ⇒
              p.success(value)
              // There's a race condition here, that a second write attempt might get here before the first
              // one was finished. This is illegal usage anyway and will fail when the promise will be completed the second time.
              store.put(key, value.asInstanceOf[AnyRef] /* values are always boxed on the JVM */ )
            case x ⇒ throw new IllegalStateException(s"${key.name} value was already set to $x. Can't set twice.")
          }
        else {
          val res = store.putIfAbsent(key, value.asInstanceOf[AnyRef] /* values are always boxed on the JVM */ )
          if (res ne null) put(key, value) // retry, reader got here first
          // else nothing to do, further readers will get the value directly
        }
    }

  /** Marker class to put into slot while there are some waiting readers */
  private case class Waiting(p: Promise[_])
}

private[akka] abstract class StashableGraphStageLogic(shape: Shape) extends GraphStageLogic(shape) {
  def stashAndWait[T](f: Future[T])(handler: Try[T] ⇒ Unit): Unit = {
    val unstash = stashHandlers()
    f.onComplete(getAsyncCallback[Try[T]] { result ⇒
      unstash()
      handler(result)
    }.invoke)(materializer.executionContext)
  }

  def stashHandlers(): () ⇒ Unit = {
    var deferredSignals = new VectorBuilder[() ⇒ Unit]
    var resetHandler = new VectorBuilder[() ⇒ Unit]
    def defer(body: ⇒ Unit): Unit = deferredSignals += body _

    def stashInHandler(inlet: Inlet[_]): Unit = {
      val previous = getHandler(inlet)
      resetHandler += (() ⇒ setHandler(inlet, previous))
      setHandler(inlet, new InHandler {
        def onPush(): Unit = defer(previous.onPush())

        override def onUpstreamFinish(): Unit = defer(previous.onUpstreamFinish())
        override def onUpstreamFailure(ex: Throwable): Unit = defer(previous.onUpstreamFailure(ex))
      })
    }
    def stashOutHandler(outlet: Outlet[_]): Unit = {
      val previous = getHandler(outlet)
      resetHandler += (() ⇒ setHandler(outlet, previous))
      setHandler(outlet, new OutHandler {
        def onPull(): Unit = defer(previous.onPull())
        override def onDownstreamFinish(): Unit = defer(previous.onDownstreamFinish())
      })
    }

    shape.inlets.foreach(stashInHandler)
    shape.outlets.foreach(stashOutHandler)

    () ⇒ { // unstash method
      resetHandler.result().foreach(_()) // first reset all handlers
      deferredSignals.result().foreach(_()) // then run all deferred signals
    }
  }
}

private[akka] trait StageValueMapSupport extends StashableGraphStageLogic {
  def attributes: Attributes

  val stageMap =
    attributes.get[StageValueMap]
      .getOrElse(throw new IllegalStateException(
        "StageValueMap attribute missing. Make sure to add an empty " +
          "StageValueMap before Materialization."))

  def getStageValue[T](key: StageValueKey[T]): Future[T] = stageMap.get(key)

  def stashAndWaitForStageValue[T](key: StageValueKey[T])(handler: Try[T] ⇒ Unit): Unit =
    stashAndWait(getStageValue(key))(handler)

  def stashAndWaitForRequiredStageValue[T](key: StageValueKey[T])(handler: T ⇒ Unit): Unit =
    stashAndWait(getStageValue(key)) {
      case Success(value) ⇒ handler(value)
      case Failure(ex)    ⇒ failStage(ex)
    }
}

object Data {
  case object SomeInt extends StageValueKey[Int]("someInt")
  case object SomeInt2 extends StageValueKey[Int]("someInt2")
}
object Usage extends StashableGraphStageLogic(null) with StageValueMapSupport {
  def attributes: Attributes = ???

  var i = 0
  var j = 1
  stashAndWaitForRequiredStageValue(Data.SomeInt) { i ⇒
    this.i = i

    stashAndWaitForRequiredStageValue(Data.SomeInt2) { j ⇒
      this.j = j
    }
  }
}