/*
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.tracing

import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.HttpMessage
import akka.http.scaladsl.server.{ Directive0, Route, RouteConcatenation, RouteResult }
import akka.http.scaladsl.util.FastFuture
import FastFuture._
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

trait DirectiveInfo {

}

object DirectiveInfo {

}

object RouteTracing {
  object Implicits {
    implicit def _enhanceRouteWithConcatenation(route: Route): RouteConcatenation.RouteWithConcatenation =
      new MyConcatImpl(route, NoRouteInfo)

    implicit def addByNameNullaryApply(directive: Directive0): RouteApply0 =
      new RouteApply0(directive)
  }

  private class MyConcatImpl(route: Route, secondRouteInfo: RouteInfo) extends RouteConcatenation.RouteWithConcatenation(route) {
    override def ~(other: Route): Route = { ctx ⇒
      import ctx.executionContext
      route(ctx).fast.flatMap {
        case x: RouteResult.Complete ⇒
          val newResponse = updateTracingInfoHeader(first ⇒
            FirstSucceeded(first, secondRouteInfo)
          )(x.response)

          FastFuture.successful(x.copy(response = newResponse))

        case r @ RouteResult.Rejected(outerRejections) ⇒
          val firstTrace = Option(r.context.asInstanceOf[Trace]).getOrElse(NoTrace)

          other(ctx).fast.map {
            case x: RouteResult.Complete ⇒
              val newResponse = updateTracingInfoHeader(second ⇒
                SecondSucceeded(firstTrace, second)
              )(x.response)

              x.copy(response = newResponse)
            case r2 @ RouteResult.Rejected(innerRejections) ⇒
              val secondTrace = Option(r2.context.asInstanceOf[Trace]).getOrElse(NoTrace)

              RouteResult.Rejected(outerRejections ++ innerRejections)
                .withContext(BothRejected(firstTrace, secondTrace))
          }
      }
    }
  }

  class RouteApply0(directive: Directive0) {
    def apply(innerRoute: Route): Route = {
      println(s"Got called with ${directive.getClass} -> ${innerRoute.getClass}")

      { ctx ⇒
        import ctx.executionContext
        @volatile var innerTrace: Future[Trace] = Future.successful(NoTrace)
        val directiveRoute = directive.tapply { _ ⇒ innerCtx ⇒
          val innerResult = innerRoute(innerCtx)
          innerTrace = innerResult.fast.map(traceOf)
          innerResult
        }
        val completeResult = directiveRoute(ctx)

        innerTrace.fast.flatMap { innerTrace ⇒
          completeResult.fast.map { outerResult ⇒
            updateTrace(outerResult)(outerTrace ⇒ CompositeTrace(NoRouteInfo, outerResult, outerTrace, innerTrace))
          }
        }
      }
    }
  }

  def traceOf(routeResult: RouteResult): Trace = routeResult match {
    case x: RouteResult.Complete ⇒
      x.response.header[TracingInfoHeader]
        .map(_.info)
        .getOrElse(NoTrace)
    case r: RouteResult.Rejected ⇒
      Option(r.context.asInstanceOf[Trace]).getOrElse(NoTrace)
  }
  def updateTrace(routeResult: RouteResult)(f: Trace ⇒ Trace): RouteResult =
    routeResult match {
      case x: RouteResult.Complete ⇒
        x.copy(response = updateTracingInfoHeader(f)(x.response))
      case x: RouteResult.Rejected ⇒
        val oldTrace = traceOf(x)
        val newTrace = f(oldTrace)
        x.withContext(newTrace)
    }

  def updateTracingInfoHeader[T <: HttpMessage](f: Trace ⇒ Trace): T ⇒ T#Self = { msg ⇒
    val lastInfo =
      msg.header[TracingInfoHeader]
        .map(_.info)
        .getOrElse(NoTrace)
    val newInfo = f(lastInfo)
    msg.withHeaders(TracingInfoHeader(newInfo) +: msg.headers.filterNot(_.isInstanceOf[TracingInfoHeader]))
  }
}

case class TracingInfoHeader(info: Trace) extends InternalCustomHeader("RouteTracingHeader") {

}
//case class TracingInfo(trace: Trace)

trait RouteInfo
case object NoRouteInfo extends RouteInfo

sealed trait Trace {
  def result: RouteResult
}
object Trace {

  case class Stream(f: () ⇒ Next) extends (() ⇒ Next) {
    def apply(): Next = f()
    def prepend(msg: String): Stream = Stream(() ⇒ Some((msg, this)))
    def ::(msg: String): Stream = prepend(msg)
    def append(other: Stream): Stream =
      Stream { () ⇒
        f() match {
          case Some((msg, next)) ⇒ Some((msg, next.append(other)))
          case None              ⇒ other()
        }
      }
    def ++(other: Stream): Stream = append(other)
    def :+(msg: String): Stream = append(Stream.single(msg))
  }
  object Stream {
    def empty: Stream = Stream(() ⇒ None)
    def single(msg: String): Stream = msg :: empty

    def deferred(str: ⇒ Stream): Stream = Stream(() ⇒ str())
  }

  type Next = Option[(String, Stream)]
  def format(trace: Trace): Source[String, NotUsed] = {
    def oneStep(trace: Trace): Stream = trace match {
      case FirstSucceeded(firstResult, _) ⇒
        ("Enter First branch of concat" ::
          Stream.deferred(oneStep(firstResult))) :+ s"First branch produced result: [${firstResult.result}]"

      //case SecondSucceeded()
      case x ⇒ s"Unknown [$x]" :: Stream.empty
    }

    Source.unfold(oneStep(trace)) { f ⇒
      f() match {
        case Some((string, cont)) ⇒ Some(cont -> string)
        case None                 ⇒ None
      }
    }
  }
}

case object NoTrace extends Trace {
  def result = RouteResult.Rejected(Nil)
}

sealed trait AlternativeTrace extends Trace
case class FirstSucceeded(firstResult: Trace, secondInfo: RouteInfo) extends AlternativeTrace {
  def result = firstResult.result
}
case class SecondSucceeded(firstResult: Trace, secondResult: Trace) extends AlternativeTrace {
  def result = secondResult.result
}
case class BothRejected(firstResult: Trace, secondResult: Trace) extends AlternativeTrace {
  def result = firstResult.result // FIXME: should take both into account
}

case class RouteResultTrace(routeInfo: RouteInfo, result: RouteResult) extends Trace
case class CompositeTrace(routeInfo: RouteInfo, overallResult: RouteResult, outerTrace: Trace, innerTrace: Trace) extends Trace {
  def result = overallResult
}

