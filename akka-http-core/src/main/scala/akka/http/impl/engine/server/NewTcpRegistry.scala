package akka.io

import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.io.SelectionHandler.ChannelRegistryImpl
import akka.util.SerializedSuspendableExecutionContext

class ChannelRegistryExt(implicit val system: ExtendedActorSystem) extends akka.actor.Extension {
  private val settings = Tcp(system).Settings
  private val SelectorDispatcher = settings.SelectorDispatcher
  val registry = {
    val dispatcher = system.dispatchers.lookup(SelectorDispatcher)
    new ChannelRegistryImpl(SerializedSuspendableExecutionContext(dispatcher.throughput)(dispatcher), settings, system.log)
  }
}
object ChanReg extends ExtensionId[ChannelRegistryExt] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): ChannelRegistryExt = new ChannelRegistryExt()(system)
  override def lookup(): ExtensionId[_ <: Extension] = ChanReg
}
