/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ThreadLocalRandom

object TestUtils {
  /**
   * Returns InetSocketAddress of a loopback interface that is likely to be free for
   * binding.
   */
  def temporaryServerAddress(): InetSocketAddress =
    temporaryServerAddress(randomLoopbackAddress())

  /**
   * Returns InetSocketAddress, host name, and port of a loopback interface that is likely to be free for
   * binding.
   */
  def temporaryServerHostnameAndPort(): (InetSocketAddress, String, Int) =
    temporaryServerHostnameAndPort(randomLoopbackAddress())

  /**
   * Returns a loopback address in the 127.0.0.0/8 subnet.
   */
  private def randomLoopbackAddress(min: Int = 0): String = {
    def ipComponent(min: Int = 0): Int = ThreadLocalRandom.current().nextInt(min, 256)
    s"127.${ipComponent()}.${ipComponent()}.${ipComponent(1)}" // unclear if 127.0.0.0 is a valid address
  }

  private def temporaryServerHostnameAndPort(interface: String): (InetSocketAddress, String, Int) = {
    val socketAddress = temporaryServerAddress(interface)
    (socketAddress, socketAddress.getHostString, socketAddress.getPort)
  }

  /**
   * Tries to find a random port by opening a listening socket on port 0 and
   * returning the address after immediately closing it again.
   */
  private def temporaryServerAddress(interface: String): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.socket.bind(new InetSocketAddress(interface, 0))
      val port = serverSocket.socket.getLocalPort
      new InetSocketAddress(interface, port)
    } finally serverSocket.close()
  }
}
