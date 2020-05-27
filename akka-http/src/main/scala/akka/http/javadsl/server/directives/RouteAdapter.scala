/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.javadsl.server.{ ExceptionHandler, RejectionHandler, Route }
import akka.annotation.InternalApi
import akka.http.javadsl.settings.{ ParserSettings, RoutingSettings }
import akka.http.scaladsl
import akka.http.scaladsl.server.RouteConcatenation._
import akka.stream.{ Materializer, javadsl }
import akka.stream.scaladsl.Flow

/** INTERNAL API */
@InternalApi
final class RouteAdapter(val delegate: akka.http.scaladsl.server.Route) extends Route {

  override def flow(system: ActorSystem, materializer: Materializer): javadsl.Flow[HttpRequest, HttpResponse, NotUsed] =
    scalaFlow(system, materializer).asJava

  private def scalaFlow(system: ActorSystem, materializer: Materializer): Flow[HttpRequest, HttpResponse, NotUsed] = {
    implicit val s: ActorSystem = system
    Flow[HttpRequest].map(_.asScala).via(delegate).map(_.asJava)
  }

  override def orElse(alternative: Route): Route =
    alternative match {
      case adapt: RouteAdapter =>
        RouteAdapter(delegate ~ adapt.delegate)
    }

  override def seal(): Route = RouteAdapter(scaladsl.server.Route.seal(delegate))

  override def seal(rejectionHandler: RejectionHandler, exceptionHandler: ExceptionHandler): Route =
    RouteAdapter(scaladsl.server.Route.seal(delegate)(
      rejectionHandler = rejectionHandler.asScala,
      exceptionHandler = exceptionHandler.asScala))

  override def toString = s"akka.http.javadsl.server.Route($delegate)"
}

object RouteAdapter {
  def apply(delegate: akka.http.scaladsl.server.Route): RouteAdapter = new RouteAdapter(delegate)

  /** Java DSL: Adapt an existing ScalaDSL Route as an Java DSL Route */
  def asJava(delegate: akka.http.scaladsl.server.Route): Route = new RouteAdapter(delegate)
}
