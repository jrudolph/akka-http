package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.TestServer.routes
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.control.NonFatal

object PoolHangsTest extends App {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.log-dead-letters = off
    akka.stream.materializer.debug.fuzzing-mode = off

    akka.http.host-connection-pool {
      max-connections = 4
    }
    akka.http.client.idle-timeout = 3s
    akka.http.client.log-unencrypted-network-bytes = 500
    akka.io.tcp.trace-logging = on
    """)

  implicit val system = ActorSystem("ServerTest", testConf)
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import Directives._

  val routes =
    post {
      path("abc") {
        entity(as[String]) { str ⇒
          import akka.pattern.after

          complete(after(100.millis, system.scheduler)(Future.successful(s"Yep, success: $str")))
        }
      }
    }

  try {
    Await.result(Http().bindAndHandle(routes, interface = "0.0.0.0", port = 8080), 5.seconds)
    val theReq = HttpRequest(HttpMethods.POST, uri = "http://localhost:8080/abc", entity = HttpEntity("abc"))
    val a = Http().singleRequest(theReq)
    val b = Http().singleRequest(theReq)
    val c = Http().singleRequest(theReq)
    val d = Http().singleRequest(theReq)
    println(Await.result(a, 10.seconds))
    println(Await.result(b, 10.seconds))
    Thread.sleep(2000)
    Http().singleRequest(theReq)
    Http().singleRequest(theReq)
    Http().singleRequest(theReq)
    Thread.sleep(2000)
    Http().singleRequest(theReq)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))
    Thread.sleep(2000)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))
    Thread.sleep(2000)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))
    Thread.sleep(2000)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))
    Thread.sleep(2000)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))
    Thread.sleep(2000)
    println(Await.result(Http().singleRequest(theReq), 10.seconds))

  } finally system.terminate()
}
