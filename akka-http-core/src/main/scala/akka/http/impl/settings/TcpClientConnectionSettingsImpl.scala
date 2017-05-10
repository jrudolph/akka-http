/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.InternalApi
import akka.http.impl.util.SettingsCompanion
import akka.http.scaladsl.settings.TcpClientConnectionSettings
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

@InternalApi
private[http] case class TcpClientConnectionSettingsImpl(
  connectingTimeout: FiniteDuration,
  idleTimeout:       Duration,
  socketOptions:     immutable.Seq[SocketOption]
) extends TcpClientConnectionSettings

object TcpClientConnectionSettingsImpl extends SettingsCompanion[TcpClientConnectionSettingsImpl]("akka.http.client.transport") {
  def fromSubConfig(root: Config, c: Config): TcpClientConnectionSettingsImpl = ???
}