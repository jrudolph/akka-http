/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream._
import akka.stream.scaladsl._

object WssIssueReplicator extends App {
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val ec = system.dispatcher

  val outgoing =
    Source.single(TextMessage("hello world!"))

  val webSocketFlow = Http().webSocketClientFlow(
    WebSocketRequest("wss://unknown:8433"),
    settings = ClientConnectionSettings(system).withLogUnencryptedNetworkBytes(Some(100000))
  )

  val incoming =
    Sink.foreach[Message] {
      case message: TextMessage.Strict ⇒
        println(message.text)
    }

  // upgradeResponse is a Future[WebSocketUpgradeResponse]
  // and it's expected to complete with success or failure ...
  val (upgradeResponse, closed) =
    outgoing
      .viaMat(webSocketFlow)(Keep.right)
      .toMat(incoming)(Keep.both)
      .run()

  upgradeResponse.onComplete { res ⇒
    println(s"Got result: $res")
  }

  // ... BUT ... it never completes in case of failures!
  upgradeResponse.failed.foreach { ex ⇒
    ex.printStackTrace()
    System.exit(-1)
  }
}