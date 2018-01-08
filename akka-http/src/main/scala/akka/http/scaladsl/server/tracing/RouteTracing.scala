/*
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.tracing

import akka.http.impl.engine.ws.InternalCustomHeader
import akka.http.scaladsl.model.HttpMessage
import akka.http.scaladsl.server._
import akka.http.scaladsl.util.FastFuture
import FastFuture._
import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.collection.immutable
import scala.concurrent.Future

import scala.reflect.macros.blackbox

object RouteTracing {
  object Implicits {
    implicit def _enhanceRouteWithConcatenation(route: Route): RouteConcatenation.RouteWithConcatenation =
      new MyConcatImpl(route, NoStaticInfo)

    implicit def addByNameNullaryApply(directive: Directive0): RouteApply0 = macro apply0Impl

  }

  def apply0Impl(c: blackbox.Context)(directive: c.Expr[Directive0]): c.Expr[RouteApply0] = {
    import c._
    import c.universe._

    val directiveInfo: c.Expr[StaticInfo] = directive.tree match {
      case x @ q"$prefix.$name[..$targs](...$args)" ⇒
        val str = name.toString
        val parms = if (args.isEmpty) "" else s"(${args.head.mkString(", ")})"

        val line = x.pos.source.lineToString(x.pos.line - 1)
        println(s"$str$parms pos: ${x.pos}")
        reify(DirectiveInfo(c.literal(str + parms).splice, c.literal(line).splice))
      case x ⇒
        println(s"${Console.RED}Couldn't match [$x] ${Console.RESET}")
        reify(NoStaticInfo)
    }
    //println(directive)
    //println(showRaw(directive))
    //println(directiveInfo) ///sv

    reify {
      new RouteApply0(directive.splice, directiveInfo.splice)
    }
  }

  private class MyConcatImpl(route: Route, secondRouteInfo: StaticInfo) extends RouteConcatenation.RouteWithConcatenation(route) {
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

  class RouteApply0(directive: Directive0, directiveInfo: StaticInfo = NoStaticInfo) {
    def apply(innerRoute: Route): Route = {
      println(s"Got called with ${directive.getClass} -> ${innerRoute.getClass}")

      { ctx ⇒
        import ctx.executionContext
        @volatile var innerTrace: Future[Trace] = Future.successful(NotExecuted())
        val directiveRoute = directive.tapply { _ ⇒ innerCtx ⇒
          val innerResult = innerRoute(innerCtx)
          innerTrace = innerResult.fast.map { res ⇒
            traceOf(res).orElse(ResultAndInfoTrace(res))
          }
          innerResult.fast.map { res ⇒
            updateTrace(res)(_ ⇒ NoTrace) // discard inner trace so that it won't be recorded again by outerTrace
          }
        }
        val completeResult = directiveRoute(ctx)

        innerTrace.fast.flatMap { innerTrace ⇒
          completeResult.fast.map { outerResult ⇒
            updateTrace(outerResult)(outerTrace ⇒ CompositeTrace(directiveInfo, outerResult, outerTrace.orElse(ResultAndInfoTrace(outerResult, directiveInfo)), innerTrace))
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

trait StaticInfo
case object NoStaticInfo extends StaticInfo
case class DirectiveInfo(name: String, line: String = "") extends StaticInfo

sealed trait Trace {
  def staticInfo: StaticInfo
  def resultString: String = resultOption.map(_.toString).getOrElse("<no result>")
  def resultOption: Option[RouteResult]

  /** Return this trace if defined or other if this == NoTrace */
  def orElse(other: Trace): Trace = this
}
object Trace {
  // basically the same as Scala stream class but doesn't cache results of computation
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
    def ++(other: ⇒ Stream): Stream = append(other)
    def :+(msg: String): Stream = append(Stream.single(msg))
  }
  object Stream {
    def empty: Stream = Stream(() ⇒ None)
    def single(msg: String): Stream = msg :: empty

    def deferred(str: ⇒ Stream): Stream = Stream(() ⇒ str())
  }

  type Next = Option[(String, Stream)]
  def format(trace: Trace): Source[String, NotUsed] = {
    def oneStep(trace: Trace, indent: String): Stream = {
      val nextIndent = indent + "  "
      // automatically applies indentation for inner calls
      def innerStep(trace: Trace): Stream = oneStep(trace, nextIndent)

      trace match {
        case FirstSucceeded(firstTrace, _) ⇒
          (s"${indent}Entering first branch of concat (${firstTrace.staticInfo})" ::
            Stream.deferred(innerStep(firstTrace))) :+
            s"${indent}First branch of concat produced result: [${firstTrace.resultString}]"

        case SecondSucceeded(firstTrace, secondTrace) ⇒
          (s"${indent}Entering first branch of concat" :: Stream.deferred(innerStep(firstTrace))) ++
            (s"${indent}First branch of concat rejected with ${firstTrace.resultString}, entering second branch" :: innerStep(secondTrace)) :+
            s"${indent}Second branch of concat produced result: [${secondTrace.resultString}]"

        case BothRejected(firstTrace, secondTrace) ⇒
          (s"${indent}Entering first branch of concat" :: Stream.deferred(innerStep(firstTrace))) ++
            (s"${indent}First branch of concat rejected with ${firstTrace.resultString}, entering second branch" :: innerStep(secondTrace)) :+
            s"${indent}Second branch of concat rejected with: [${secondTrace.resultString}]. Total result: ${trace.resultString}"

        case CompositeTrace(info, overall, outer, inner) ⇒
          (s"${indent}Entering directive [$info], inner trace following" :: Stream.deferred(innerStep(inner))) ++
            (s"${indent}Inner produced result [${inner.resultString}], outer trace following" :: innerStep(outer)) :+
            s"${indent}Exiting directive with result [$overall]"

        case NoTrace                                ⇒ Stream.single(s"${indent}No tracing information")
        case ResultAndInfoTrace(result, staticInfo) ⇒ Stream.single(s"${indent}Route produced result [$result]")
        case NotExecuted(_)                         ⇒ Stream.single(s"${indent}Not executed.")
        case x                                      ⇒ s"${indent}Unknown [$x]" :: Stream.empty
      }
    }

    Source.unfold(oneStep(trace, "")) { f ⇒
      f() match {
        case Some((string, cont)) ⇒ Some(cont -> string)
        case None                 ⇒ None
      }
    }
  }
}

case class ResultAndInfoTrace(result: RouteResult, staticInfo: StaticInfo = NoStaticInfo) extends Trace {
  def resultOption = Some(result)
}
case object NoTrace extends Trace {
  def staticInfo = NoStaticInfo
  def resultOption = None
  override def orElse(other: Trace) = other
}
case class NotExecuted(staticInfo: StaticInfo = NoStaticInfo) extends Trace {
  def resultOption = None
}

sealed trait AlternativeTrace extends Trace
case class FirstSucceeded(firstResult: Trace, secondInfo: StaticInfo) extends AlternativeTrace {
  def staticInfo = firstResult.staticInfo

  def resultOption = firstResult.resultOption
}
case class SecondSucceeded(firstResult: Trace, secondResult: Trace) extends AlternativeTrace {
  def staticInfo = firstResult.staticInfo
  def resultOption = secondResult.resultOption
}
case class BothRejected(firstResult: Trace, secondResult: Trace) extends AlternativeTrace {
  def staticInfo = firstResult.staticInfo
  def resultOption = {
    def rejs(r: Option[RouteResult]): immutable.Seq[Rejection] = r match {
      case Some(RouteResult.Rejected(rs)) ⇒ rs
      case _                              ⇒ Nil
    }
    Some(RouteResult.Rejected(rejs(firstResult.resultOption) ++ rejs(secondResult.resultOption)))
  }
}

//case class RouteResultTrace(staticInfo: StaticInfo, result: RouteResult) extends Trace
case class CompositeTrace(staticInfo: StaticInfo, overallResult: RouteResult, outerTrace: Trace, innerTrace: Trace) extends Trace {
  def resultOption = Some(overallResult)
}

