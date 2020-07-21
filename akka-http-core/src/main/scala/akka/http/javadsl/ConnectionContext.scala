/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl

import java.util.{ Optional, Collection => JCollection }

import akka.annotation.DoNotInherit
import akka.http.scaladsl
import akka.japi.Util
import akka.stream.TLSClientAuth
import com.github.ghik.silencer.silent
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import javax.net.ssl.{ SSLContext, SSLParameters }

import scala.compat.java8.OptionConverters

object ConnectionContext {
  //#https-context-creation
  def httpsClient(sslContext: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.httpsClient(sslContext)

  //#https-context-creation
  def httpsServer(sslContext: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.httpsServer(sslContext)

  // ConnectionContext
  /** Used to serve HTTPS traffic. */
  @Deprecated @deprecated("use httpsServer, httpsClient or the method that takes a custom factory", since = "10.2.0")
  @silent("since 10.2.0")
  def https(sslContext: SSLContext): HttpsConnectionContext = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.https(sslContext)

  /** Used to serve HTTPS traffic. */
  @Deprecated @deprecated("use httpsServer, httpsClient or the method that takes a custom factory", since = "10.2.0")
  @silent("since 10.2.0")
  def https(
    sslContext:          SSLContext,
    sslConfig:           Optional[AkkaSSLConfig],
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) = // ...
    //#https-context-creation
    scaladsl.ConnectionContext.https(
      sslContext,
      OptionConverters.toScala(sslConfig),
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters))

  /** Used to serve HTTPS traffic. */
  @Deprecated @deprecated("use httpsServer, httpsClient or the method that takes a custom factory", since = "10.2.0")
  @silent("since 10.2.0")
  def https(
    sslContext:          SSLContext,
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(
      sslContext,
      None,
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters))

  /** Used to serve HTTP traffic. */
  def noEncryption(): HttpConnectionContext =
    scaladsl.ConnectionContext.noEncryption()
}

@DoNotInherit
abstract class ConnectionContext {
  def isSecure: Boolean
  @Deprecated
  @deprecated("Not always avaialble", since = "10.2.0")
  def sslConfig: Option[AkkaSSLConfig]
}

@DoNotInherit
abstract class HttpConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = false
  override def sslConfig: Option[AkkaSSLConfig] = None
}

@DoNotInherit
abstract class HttpsConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = true

  /** Java API */
  @Deprecated @deprecated("here for binary compatibility", since = "10.2.0")
  def getEnabledCipherSuites: Optional[JCollection[String]]
  /** Java API */
  @Deprecated @deprecated("here for binary compatibility", since = "10.2.0")
  def getEnabledProtocols: Optional[JCollection[String]]
  /** Java API */
  @Deprecated @deprecated("here for binary compatibility", since = "10.2.0")
  def getClientAuth: Optional[TLSClientAuth]

  /** Java API */
  @Deprecated @deprecated("here for binary compatibility, not always available", since = "10.2.0")
  def getSslContext: SSLContext
  /** Java API */
  @Deprecated @deprecated("here for binary compatibility", since = "10.2.0")
  def getSslParameters: Optional[SSLParameters]
}
