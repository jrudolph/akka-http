/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress

import akka.http.scaladsl.model.HttpEntity
import akka.stream.Attributes
import akka.stream.Attributes.Attribute

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 * Internally used attributes set in the HTTP pipeline.
 * May potentially be opened up in the future.
 */
private[akka] object HttpAttributes {
  import Attributes._

  private[akka] final case class RemoteAddress(address: Option[InetSocketAddress]) extends Attribute

  private[akka] def remoteAddress(address: Option[InetSocketAddress]) =
    Attributes(RemoteAddress(address))

  private[akka] val empty =
    Attributes()
}

private[http] trait Strictifiable extends Attribute {
  def toStrict(timeout: FiniteDuration): Future[HttpEntity.Strict]
}