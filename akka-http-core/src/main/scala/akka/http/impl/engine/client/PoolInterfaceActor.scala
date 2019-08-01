/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.Done
import akka.actor._
import akka.pattern.ask
import akka.event.{ LogSource, Logging, LoggingAdapter }
import akka.http.impl.engine.client.PoolFlow._
import akka.http.impl.engine.client.pool.NewHostConnectionPool
import akka.http.impl.util.RichHttpRequest
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.http.scaladsl.settings.PoolImplementation
import akka.macros.LogHelper
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.OverflowStrategy
import akka.stream.actor.ActorPublisherMessage._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.actor.{ ActorPublisher, ActorSubscriber, ZeroRequestStrategy }
import akka.stream.impl.{ Buffer, SeqActorName }
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.stream.{ BufferOverflowException, Materializer }
import akka.util.Timeout

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

private[http] trait PoolInterface {
  def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit
  def shutdown()(implicit ec: ExecutionContext): Future[Done]
}

private object PoolInterfaceActor {
  def createOld(gateway: PoolGateway, parent: ActorRefFactory)(implicit fm: Materializer): PoolInterface = new PoolInterface {
    val ref = parent.actorOf(props(gateway), name.next())
    def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit =
      ref ! PoolRequest(request, responsePromise)
    def shutdown()(implicit ec: ExecutionContext): Future[Done] =
      ref.ask(Shutdown)(Timeout(30.seconds)).mapTo[Done] // FIXME
  }
  def create(gateway: PoolGateway, parent: ActorRefFactory)(implicit fm: Materializer): PoolInterface = {
    val GatewayLogSource: LogSource[PoolGateway] =
      new LogSource[PoolGateway] {
        def genString(gateway: PoolGateway): String = {
          val scheme = if (gateway.hcps.setup.connectionContext.isSecure) "https" else "http"
          s"Pool(${gateway.gatewayId.name}->$scheme://${gateway.hcps.host}:${gateway.hcps.port})"
        }
        override def genString(gateway: PoolGateway, system: ActorSystem): String = s"${system.name}/${genString(gateway)}"

        override def getClazz(t: PoolGateway): Class[_] = classOf[PoolGateway]
      }

    //import context.system
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

    Flow.fromGraph(new PoolInterfaceStage(gateway, log))
      .buffer((settings.maxOpenRequests - settings.maxConnections) max 0, OverflowStrategy.backpressure) // FIXME: is that accurate enough?
      .join(poolFlow)
      .run()
  }

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
      object Logic extends GraphStageLogic(shape) with PoolInterface with InHandler with OutHandler {
        import gateway.hcps

        setHandlers(responseIn, requestOut, this)

        override def preStart(): Unit = pull(responseIn)

        override def onPush(): Unit = {
          val ResponseContext(rc, response) = grab(responseIn)
          rc.responsePromise.complete(response)
          pull(responseIn)
        }
        override def onPull(): Unit = () // just noting the

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
              push(requestOut, RequestContext(effectiveRequest, responsePromise, retries))
            } else {
              responsePromise.tryFailure(PoolOverflowException)
            }
        }
        val shutdownCallback = getAsyncCallback[Promise[Done]] { p =>

        }

        override def request(request: HttpRequest, responsePromise: Promise[HttpResponse]): Unit = requestCallback.invoke(request, responsePromise)
        override def shutdown()(implicit ec: ExecutionContext): Future[Done] = {
          val promise = Promise[Done]()
          shutdownCallback.invoke(promise)
          promise.future
        }
      }
      (Logic, Logic)
    }
  }

  private final case class PoolRequest(request: HttpRequest, responsePromise: Promise[HttpResponse]) extends NoSerializationVerificationNeeded

  private case object Shutdown extends DeadLetterSuppression

  private val name = SeqActorName("PoolInterfaceActor")

  private def props(gateway: PoolGateway)(implicit fm: Materializer) =
    Props(new PoolInterfaceActor(gateway)).withDeploy(Deploy.local)

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

/**
 * An actor that wraps a completely encapsulated, running connection pool flow.
 *
 * Outside interface:
 *   The actor accepts `PoolRequest` messages and completes their `responsePromise` when the respective
 *   response has arrived. Incoming `PoolRequest` messages are not back-pressured but rather buffered in
 *   a fixed-size ringbuffer if required. Requests that would cause a buffer overflow are completed with
 *   a respective error. The user can prevent buffer overflows by configuring a `max-open-requests` value
 *   that is >= max-connections x pipelining-limit x number of respective client-flow materializations.
 *
 * Inside interface:
 *   To the inside (i.e. the running connection pool flow) the gateway actor acts as request source
 *   (ActorPublisher) and response sink (ActorSubscriber).
 */
private class PoolInterfaceActor(gateway: PoolGateway)(implicit fm: Materializer)
  extends ActorSubscriber with ActorPublisher[RequestContext] with LogHelper {
  override val log: LoggingAdapter = Logging(context.system, gateway)(PoolInterfaceActor.GatewayLogSource)

  import PoolInterfaceActor._

  private[this] val hcps = gateway.hcps
  private[this] val inputBuffer = Buffer[PoolRequest](hcps.setup.settings.maxOpenRequests, fm)
  private[this] var activeIdleTimeout: Option[Cancellable] = None

  /*
   * If true, the pool is shutting down and waiting for ongoing requests to be completed. Requests coming in in this state
   * will be redispatched through the gateway. Once all requests were handled this pool shuts down itself.
   */
  private[this] var shuttingDown: Boolean = false
  var shutdownRequesters: Set[ActorRef] = Set.empty

  private[this] val PoolOverflowException = new BufferOverflowException( // stack trace cannot be prevented here because `BufferOverflowException` is final
    s"Exceeded configured max-open-requests value of [${inputBuffer.capacity}]. This means that the request queue of this pool (${gateway.hcps}) " +
      s"has completely filled up because the pool currently does not process requests fast enough to handle the incoming request load. " +
      "Please retry the request later. See http://doc.akka.io/docs/akka-http/current/scala/http/client-side/pool-overflow.html for " +
      "more information.")

  log.debug("(Re-)starting host connection pool to {}:{}", hcps.host, hcps.port)

  initConnectionFlow()

  /** Start the pool flow with this actor acting as source as well as sink */
  private def initConnectionFlow() = {
    import context.system
    import hcps._
    import setup.{ connectionContext, settings }

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

    Source.fromPublisher(ActorPublisher(self)).via(poolFlow).runWith(Sink.fromSubscriber(ActorSubscriber[ResponseContext](self)))
  }

  activateIdleTimeoutIfNecessary()

  def requestStrategy = ZeroRequestStrategy

  def receive = {

    /////////////// COMING UP FROM POOL (SOURCE SIDE) //////////////

    case Request(_) => dispatchRequests() // the pool is ready to take on more requests

    case Cancel     =>
    // somehow the pool shut down, however, we don't do anything here because we'll also see an
    // OnComplete or OnError which we use as the sole trigger for cleaning up

    /////////////// COMING DOWN FROM POOL (SINK SIDE) //////////////

    case OnNext(ResponseContext(rc, responseTry)) =>
      rc.responsePromise.complete(responseTry)
      activateIdleTimeoutIfNecessary()

      if (shuttingDown && remainingRequested == 0) {
        debug("Shutting down host connection pool now after all responses have been dispatched")
        onCompleteThenStop()
      }

    case OnComplete => // the pool shut down
      debug("Host connection pool has completed orderly shutdown")
      self ! PoisonPill // give potentially queued requests another chance to be forwarded back to the gateway

    case OnError(e) => // the pool shut down
      debug(s"Host connection pool has shut down with error ${e.getMessage}")
      self ! PoisonPill // give potentially queued requests another chance to be forwarded back to the gateway

    /////////////// FROM CLIENT //////////////

    case x: PoolRequest if !shuttingDown =>
      activeIdleTimeout foreach { timeout =>
        timeout.cancel()
        activeIdleTimeout = None
      }
      if (totalDemand == 0) {
        // if we can't dispatch right now we buffer and dispatch when demand from the pool arrives
        if (inputBuffer.isFull) {
          debug(s"InputBuffer (max-open-requests = ${hcps.setup.settings.maxOpenRequests}) exhausted when trying to enqueue ${x.request.debugString}")
          x.responsePromise.failure(PoolOverflowException)
        } else {
          inputBuffer.enqueue(x)
          debug(s"InputBuffer (max-open-requests = ${hcps.setup.settings.maxOpenRequests}) now filled with ${inputBuffer.used} request after enqueuing ${x.request.debugString}")
        }
      } else dispatchRequest(x) // if we can dispatch right now, do it

    case PoolRequest(request, responsePromise) =>
      // we have already started shutting down, i.e. this pool is not usable anymore
      // so we forward the request back to the gateway
      //
      // TODO: How would that happen? Wouldn't it be a bug if the PoolMasterActor/gateway would dispatch
      //       more requests to this instance after having sent `Shutdown`?
      responsePromise.completeWith(gateway(request))

    case Shutdown => // signal coming in from gateway
      shutdownRequesters += sender
      while (!inputBuffer.isEmpty) {
        val PoolRequest(request, responsePromise) = inputBuffer.dequeue()
        responsePromise.completeWith(gateway(request))
      }

      shuttingDown = true

      if (remainingRequested == 0) {
        debug("Shutting down host connection pool immediately")
        onCompleteThenStop()
      } else
        debug(s"Deferring shutting down host connection pool until all [$remainingRequested] responses have been dispatched")
  }

  @tailrec private def dispatchRequests(): Unit =
    if (totalDemand > 0 && !inputBuffer.isEmpty) {
      dispatchRequest(inputBuffer.dequeue())
      dispatchRequests()
    }

  def dispatchRequest(pr: PoolRequest): Unit = {
    val scheme = Uri.httpScheme(hcps.setup.connectionContext.isSecure)
    val hostHeader = headers.Host(hcps.host, Uri.normalizePort(hcps.port, scheme))
    val effectiveRequest =
      pr.request
        .withUri(pr.request.uri.toHttpRequestTargetOriginForm)
        .withDefaultHeaders(hostHeader)
    val retries = if (pr.request.method.isIdempotent) hcps.setup.settings.maxRetries else 0
    onNext(RequestContext(effectiveRequest, pr.responsePromise, retries))
    request(1) // for every outgoing request we demand one response from the pool
  }

  def activateIdleTimeoutIfNecessary(): Unit =
    if (shouldStopOnIdle()) {
      import context.dispatcher
      val timeout = hcps.setup.settings.idleTimeout.asInstanceOf[FiniteDuration]
      activeIdleTimeout = Some(context.system.scheduler.scheduleOnce(timeout)(gateway.shutdown()))
    }

  private def shouldStopOnIdle(): Boolean =
    !shuttingDown && remainingRequested == 0 && hcps.setup.settings.idleTimeout.isFinite && hcps.setup.settings.minConnections == 0

  override def postStop(): Unit = shutdownRequesters.foreach(_ ! Done)
}
