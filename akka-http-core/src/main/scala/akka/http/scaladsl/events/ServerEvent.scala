package akka.http.scaladsl.events

import akka.annotation.ApiMayChange
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse

@ApiMayChange
abstract class ServerEvent

@ApiMayChange
object ServerEvent {
  abstract class RequestEvent extends ServerEvent {
    def request: HttpRequest
  }

  abstract class ReceivedRequestHeaders extends RequestEvent
  abstract class ReceivedLastByteOfRequestEntity extends RequestEvent
  abstract class DispatchedRequestToHandler extends RequestEvent

  abstract class RequestResponseEvent extends RequestEvent {
    def response: HttpResponse
  }

  abstract class ReceivedResponseFromHandler extends RequestResponseEvent
  abstract class SentOutResponseForRendering extends RequestResponseEvent
  abstract class SentOutLastByteOfResponseEntityToRendering extends RequestResponseEvent

  abstract class RequestTimedOut extends RequestResponseEvent
  abstract class RequestTimedOutBecauseOfGracefulShutdown extends RequestResponseEvent
  abstract class RequestWasDeflectedBecauseOfGracefulShutdown extends RequestResponseEvent
}

@ApiMayChange
trait ServerEventListener {
  /**
   * Receives one event for processing. This method may be called concurrently even for a single
   * connection or request.
   */
  def receive(event: ServerEvent): Unit
}
