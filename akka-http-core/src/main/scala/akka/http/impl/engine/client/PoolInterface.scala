/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.NotUsed
import akka.actor._
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.http.impl.engine.client.PoolFlow._
import akka.http.impl.engine.client.pool.NewHostConnectionPool
import akka.http.impl.util.StreamUtils
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.PoolImplementation
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import akka.stream.{ BufferOverflowException, Materializer }
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Success

/**
 * The pool interface is a push style interface to a pool of connections against a single host.
 */
private[http] trait PoolInterface {
  /**
   * Submit request to pool. After completion the pool will complete the promise with the response.
   * If the queue in front of the pool is full, the promise will be failed with a BufferOverflowException.
   */
  def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit
  def shutdown()(implicit ec: ExecutionContext): Future[Done]
  def whenShutdown: Future[Done]
}

private[http] object PoolInterface {
  def create(gateway: PoolGateway, parent: ActorRefFactory)(implicit fm: Materializer): PoolInterface = {
    import gateway.hcps
    import hcps._
    import setup.{ connectionContext, settings }
    implicit val system = fm.asInstanceOf[ActorMaterializer].system
    val log: LoggingAdapter = Logging(system, gateway)(GatewayLogSource)

    log.debug("Creating pool.")

    val connectionFlow =
      Http().outgoingConnectionUsingContext(host, port, connectionContext, settings.connectionSettings, setup.log)

    val poolFlow =
      settings.poolImplementation match {
        case PoolImplementation.Legacy =>
          log.warning("Legacy pool implementation is deprecated and will be removed in the future. " +
            "Please start using `akka.http.host-connection-pool.pool-implementation = new`, instead.")
          PoolFlow(connectionFlow, settings, log).named("PoolFlow")
        case PoolImplementation.New => NewHostConnectionPool(connectionFlow, settings, log).named("PoolFlow")
      }

    val bufferIfNeeded: Flow[RequestContext, RequestContext, NotUsed] = {
      val targetBufferSize = settings.maxOpenRequests - settings.maxConnections
      if (targetBufferSize > 0) Flow[RequestContext].buffer(targetBufferSize, OverflowStrategy.backpressure)
      else Flow[RequestContext]
    }

    Flow.fromGraph(new PoolInterfaceStage(gateway, log))
      .via(bufferIfNeeded)
      .join(poolFlow)
      .run()
  }

  private val IdleTimeout = "idle-timeout"

  class PoolInterfaceStage(gateway: PoolGateway, log: LoggingAdapter) extends GraphStageWithMaterializedValue[FlowShape[ResponseContext, RequestContext], PoolInterface] {
    private val requestOut = Outlet[RequestContext]("PoolInterface.requestOut")
    private val responseIn = Inlet[ResponseContext]("PoolInterface.responseIn")
    override def shape = FlowShape(responseIn, requestOut)

    private[this] val PoolOverflowException = new BufferOverflowException( // stack trace cannot be prevented here because `BufferOverflowException` is final
      s"Exceeded configured max-open-requests value of [${gateway.hcps.setup.settings.maxOpenRequests}]. This means that the request queue of this pool (${gateway.hcps}) " +
        s"has completely filled up because the pool currently does not process requests fast enough to handle the incoming request load. " +
        "Please retry the request later. See http://doc.akka.io/docs/akka-http/current/scala/http/client-side/pool-overflow.html for " +
        "more information.")

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, PoolInterface) = {
      object Logic extends TimerGraphStageLogic(shape) with PoolInterface with InHandler with OutHandler {
        implicit var ec: ExecutionContext = _
        import gateway.hcps

        val shutdownPromise = Promise[Done]()
        var shuttingDown: Boolean = false
        var remainingRequested = 0

        setHandlers(responseIn, requestOut, this)

        override def preStart(): Unit = {
          ec = materializer.executionContext
          pull(responseIn)
          resetIdleTimer()
        }

        override protected def onTimer(timerKey: Any): Unit = {
          log.info("Pool shutting down because idle timer triggered.")
          requestShutdown()
        }

        override def onPush(): Unit = {
          val ResponseContext(rc, response0) = grab(responseIn)
          val response1 =
            response0 match {
              case Success(r @ HttpResponse(_, _, entity, _)) if !entity.isStrict =>
                val (newEntity, termination) = StreamUtils.transformEntityStream(entity, StreamUtils.CaptureTerminationOp)
                termination.onComplete { _ => responseCompletedCallback.invoke(Done) }
                Success(r.withEntity(newEntity))
              case _ =>
                remainingRequested -= 1
                response0
            }
          rc.responsePromise.complete(response1)
          pull(responseIn)

          afterRequestFinished()
        }
        override def onPull(): Unit = () // just noting the

        val responseCompletedCallback = getAsyncCallback[Done] { _ => remainingRequested -= 1 }
        val requestCallback = getAsyncCallback[(HttpRequest, Promise[HttpResponse])] {
          case (request, responsePromise) =>
            log.debug(s"Got request $request")
            if (isAvailable(requestOut)) { // FIXME: is adding the buffer enough for proper handling or will we see unexpected results?
              log.debug("Dispatching request")
              val scheme = Uri.httpScheme(hcps.setup.connectionContext.isSecure)
              val hostHeader = headers.Host(hcps.host, Uri.normalizePort(hcps.port, scheme))
              val effectiveRequest =
                request
                  .withUri(request.uri.toHttpRequestTargetOriginForm)
                  .withDefaultHeaders(hostHeader)
              val retries = if (request.method.isIdempotent) hcps.setup.settings.maxRetries else 0
              remainingRequested += 1
              resetIdleTimer()
              push(requestOut, RequestContext(effectiveRequest, responsePromise, retries))
            } else {
              responsePromise.tryFailure(PoolOverflowException)
            }
        }
        val shutdownCallback = getAsyncCallback[Unit] { _ => requestShutdown() }

        def afterRequestFinished(): Unit = {
          shutdownIfRequestedAndPossible()
          resetIdleTimer()
        }

        def requestShutdown(): Unit = {
          shuttingDown = true
          shutdownIfRequestedAndPossible()
        }
        def shutdownIfRequestedAndPossible(): Unit =
          if (shuttingDown) {
            if (remainingRequested == 0) {
              log.info("Pool is now shutting down as requested.")
              shutdownPromise.trySuccess(Done)
              completeStage()
            } else
              log.debug(s"Pool is shutting down after waiting for [${remainingRequested}] outstanding requests.")
          }

        def resetIdleTimer(): Unit = {
          cancelTimer(IdleTimeout)

          if (shouldStopOnIdle) scheduleOnce(IdleTimeout, hcps.setup.settings.idleTimeout.asInstanceOf[FiniteDuration])
        }
        def shouldStopOnIdle: Boolean =
          !shuttingDown && remainingRequested == 0 && hcps.setup.settings.idleTimeout.isFinite && hcps.setup.settings.minConnections == 0

        // PoolInterface implementations
        override def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit =
          requestCallback.invokeWithFeedback((request, responsePromise)).failed.foreach { _ =>
            log.debug("Request was sent to pool which was already closed, retrying through the gateway")
            responsePromise.tryCompleteWith(gateway(request))
          }
        override def shutdown()(implicit ec: ExecutionContext): Future[Done] = {
          shutdownCallback.invoke(())
          whenShutdown
        }
        override def whenShutdown: Future[Done] = shutdownPromise.future
      }
      (Logic, Logic)
    }
  }

  /**
   * LogSource for PoolGateway instances
   *
   * Using this LogSource allows us to set the log class to `PoolInterfaceActor` and the log source string
   * to a descriptive name that describes a particular pool instance.
   */
  private val GatewayLogSource: LogSource[PoolGateway] =
    new LogSource[PoolGateway] {
      def genString(gateway: PoolGateway): String = {
        val scheme = if (gateway.hcps.setup.connectionContext.isSecure) "https" else "http"
        s"Pool(${gateway.gatewayId.name}->$scheme://${gateway.hcps.host}:${gateway.hcps.port})"
      }
      override def genString(gateway: PoolGateway, system: ActorSystem): String = s"${system.name}/${genString(gateway)}"

      override def getClazz(t: PoolGateway): Class[_] = classOf[PoolGateway]
    }
}
