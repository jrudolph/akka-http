/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import java.lang.Iterable
import java.util.{ Optional, Random }
import java.util.function.Supplier

import akka.annotation.DoNotInherit
import akka.http.impl.util._
import akka.http.impl.settings.ClientConnectionSettingsImpl
import akka.http.javadsl.model.headers.UserAgent
import akka.http.javadsl.{ settings ⇒ js }
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.io.Inet.SocketOption
import com.typesafe.config.Config

import scala.collection.immutable
import scala.compat.java8.OptionConverters
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.collection.JavaConverters._

/**
 * Public API but not intended for subclassing
 */
@DoNotInherit
abstract class ClientConnectionSettings private[akka] () extends akka.http.javadsl.settings.ClientConnectionSettings { self: ClientConnectionSettingsImpl ⇒
  def connectingTimeout: FiniteDuration = ??? // FIXME
  def socketOptions: immutable.Seq[SocketOption] = ??? // FIXME
  def idleTimeout: Duration = ??? // FIXME

  def transport: ClientTransport

  def userAgentHeader: Option[`User-Agent`]
  def requestHeaderSizeHint: Int
  def websocketRandomFactory: () ⇒ Random
  def parserSettings: ParserSettings
  def logUnencryptedNetworkBytes: Option[Int]

  /* JAVA APIs */

  final override def getConnectingTimeout: FiniteDuration = connectingTimeout
  final override def getParserSettings: js.ParserSettings = parserSettings
  final override def getIdleTimeout: Duration = idleTimeout
  final override def getSocketOptions: Iterable[SocketOption] = socketOptions.asJava
  final override def getUserAgentHeader: Optional[UserAgent] = OptionConverters.toJava(userAgentHeader)
  final override def getLogUnencryptedNetworkBytes: Optional[Int] = OptionConverters.toJava(logUnencryptedNetworkBytes)
  final override def getRequestHeaderSizeHint: Int = requestHeaderSizeHint
  final override def getWebsocketRandomFactory: Supplier[Random] = new Supplier[Random] {
    override def get(): Random = websocketRandomFactory()
  }

  // ---

  // overrides for more specific return type
  override def withRequestHeaderSizeHint(newValue: Int): ClientConnectionSettings = self.copy(requestHeaderSizeHint = newValue)

  // overloads for idiomatic Scala use
  def withWebsocketRandomFactory(newValue: () ⇒ Random): ClientConnectionSettings = self.copy(websocketRandomFactory = newValue)
  def withUserAgentHeader(newValue: Option[`User-Agent`]): ClientConnectionSettings = self.copy(userAgentHeader = newValue)
  def withLogUnencryptedNetworkBytes(newValue: Option[Int]): ClientConnectionSettings = self.copy(logUnencryptedNetworkBytes = newValue)
  def withParserSettings(newValue: ParserSettings): ClientConnectionSettings = self.copy(parserSettings = newValue)

  // now managed in TcpClientConnectionSettings
  override def withConnectingTimeout(newValue: FiniteDuration): ClientConnectionSettings = ??? // FIXME (deprecate?) self.copy(connectingTimeout = newValue)
  override def withIdleTimeout(newValue: Duration): ClientConnectionSettings = ??? // FIXME (deprecate?) self.copy(idleTimeout = newValue)
  def withSocketOptions(newValue: immutable.Seq[SocketOption]): ClientConnectionSettings = ??? // FIXME (deprecate?) self.copy(socketOptions = newValue)

  def withTransport(newTransport: ClientTransport): ClientConnectionSettings = self.copy(transport = newTransport)
}

object ClientConnectionSettings extends SettingsCompanion[ClientConnectionSettings] {
  override def apply(config: Config): ClientConnectionSettings = ClientConnectionSettingsImpl(config)
  override def apply(configOverrides: String): ClientConnectionSettings = ClientConnectionSettingsImpl(configOverrides)

  object LogUnencryptedNetworkBytes {
    def apply(string: String): Option[Int] =
      string.toRootLowerCase match {
        case "off" ⇒ None
        case value ⇒ Option(value.toInt)
      }
  }
}
