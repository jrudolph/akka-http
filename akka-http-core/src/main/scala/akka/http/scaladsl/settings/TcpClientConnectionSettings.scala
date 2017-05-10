/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.settings

import akka.annotation.DoNotInherit
import akka.http.impl.settings.TcpClientConnectionSettingsImpl
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }

@DoNotInherit
abstract class TcpClientConnectionSettings private[akka] () { _: TcpClientConnectionSettingsImpl ⇒
  def connectingTimeout: FiniteDuration
  def socketOptions: immutable.Seq[SocketOption]
  def idleTimeout: Duration

  def withConnectingTimeout(newValue: FiniteDuration): TcpClientConnectionSettings = copy(connectingTimeout = newValue)
  def withIdleTimeout(newValue: Duration): TcpClientConnectionSettings = copy(idleTimeout = newValue)
  def withSocketOptions(newValue: immutable.Seq[SocketOption]): TcpClientConnectionSettings = copy(socketOptions = newValue)
}

object TcpClientConnectionSettings extends SettingsCompanion[TcpClientConnectionSettings] {
  def apply(config: Config): TcpClientConnectionSettings = TcpClientConnectionSettingsImpl(config)
  def apply(configOverrides: String): TcpClientConnectionSettings = TcpClientConnectionSettingsImpl(configOverrides)
}