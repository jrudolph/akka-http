/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.scaladsl

import java.util.concurrent.TimeUnit.MILLISECONDS

import akka.actor.{ ActorRef, ActorSystem }
import akka.annotation.InternalApi
import akka.stream._
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.stream.snapshot.SnapshotEvent._
import akka.stream.snapshot._
import akka.testkit.TestProbe

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.util.control.NonFatal

object StreamTestKit {

  /**
   * Asserts that after the given code block is ran, no stages are left over
   * that were created by the given materializer.
   *
   * This assertion is useful to check that all of the stages have
   * terminated successfully.
   */
  def assertAllStagesStopped[T](block: => T)(implicit materializer: Materializer): T =
    materializer match {
      case impl: PhasedFusingActorMaterializer =>
        stopAllChildren(impl.system, impl.supervisor)
        val result = block
        assertNoChildren(impl.system, impl.supervisor)
        result
      case _ => block
    }

  /** INTERNAL API */
  @InternalApi private[testkit] def stopAllChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    probe.send(supervisor, StreamSupervisor.StopChildren)
    probe.expectMsg(StreamSupervisor.StoppedChildren)
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def assertNoChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    val c = sys.settings.config.getConfig("akka.stream.testkit")
    val timeout = c.getDuration("all-stages-stopped-timeout", MILLISECONDS).millis
    probe.within(timeout) {
      try probe.awaitAssert {
        supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
        val children = probe.expectMsgType[StreamSupervisor.Children].children
        assert(children.isEmpty, s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
      } catch {
        case ex: Throwable =>
          import sys.dispatcher
          printDebugDump(supervisor)
          throw ex
      }
    }
  }

  /** INTERNAL API */
  @InternalApi private[akka] def printDebugDump(streamSupervisor: ActorRef)(implicit ec: ExecutionContext): Unit = {
    val doneDumping = MaterializerState
      .requestFromSupervisor(streamSupervisor)
      .map(snapshots => snapshots.foreach(s => println(snapshotString(s.asInstanceOf[StreamSnapshotImpl]))))
    Await.result(doneDumping, 5.seconds)
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def snapshotString(snapshot: StreamSnapshotImpl): String = {
    val builder = new StringBuilder()
    builder.append(s"activeShells (actor: ${snapshot.self}):\n")
    snapshot.activeInterpreters.foreach { shell =>
      builder.append("  ")
      appendShellSnapshot(builder, shell)
      builder.append("\n")
      appendInterpreterSnapshot(builder, shell.asInstanceOf[RunningInterpreterImpl])
      builder.append("\n")
    }
    builder.append(s"newShells:\n")
    snapshot.newShells.foreach { shell =>
      builder.append("  ")
      appendShellSnapshot(builder, shell)
      builder.append("\n")
      builder.append("    Not initialized")
      builder.append("\n")
    }
    builder.toString
  }

  private def appendShellSnapshot(builder: StringBuilder, shell: InterpreterSnapshot): Unit = {
    builder.append("GraphInterpreterShell(\n  logics: [\n")
    val logicsToPrint = shell.logics
    logicsToPrint.foreach { logic =>
      builder
        .append("    ")
        .append(logic.label)
        .append(" attrs: [")
        .append(logic.attributes.attributeList.mkString(", "))
        .append("],\n")
    }
    builder.setLength(builder.length - 2)
    shell match {
      case running: RunningInterpreter =>
        builder.append("\n  ],\n  connections: [\n")
        running.connections.foreach { connection =>
          builder
            .append("    ")
            .append("Connection(")
            .append(connection.asInstanceOf[ConnectionSnapshotImpl].id)
            .append(", ")
            .append(connection.in.label)
            .append(", ")
            .append(connection.out.label)
            .append(", ")
            .append(connection.state)
            .append(")\n")
        }
        builder.setLength(builder.length - 2)

      case _ =>
    }
    builder.append("\n  ]\n)")
    builder.toString()
  }

  implicit def LogicImplAccess(logic: LogicSnapshot): LogicSnapshotImpl = logic.asInstanceOf[LogicSnapshotImpl]
  implicit def ConnectionImplAccess(conn: ConnectionSnapshot): ConnectionSnapshotImpl = conn.asInstanceOf[ConnectionSnapshotImpl]

  private def appendInterpreterSnapshot(builder: StringBuilder, snapshot: RunningInterpreterImpl): Unit = {
    if (snapshot.history.nonEmpty) {
      import java.io._

      snapshot.history.zipWithIndex.foreach {
        case (snap, idx) =>
          val file = new File(f"snaps/snap_$idx%04d.dot")
          file.getParentFile.mkdirs()

          val fos = new FileOutputStream(file)
          val sb = new StringBuilder(2000)
          appendInterpreterSnapshot(sb, snap)
          fos.write(sb.mkString.getBytes("utf8"))
          fos.close()
      }

      {
        val file = new File(f"snaps/event-causation.dot")
        val fos = new FileOutputStream(file)
        val sb = new StringBuilder(2000)

        val events = snapshot.history.flatMap(_.lastEvent).zipWithIndex

        events.foreach(println)

        def eventCausations(): Seq[(Int, Int)] = {
          case class State(currentlyHandling: Int, waitingForExecution: Map[ConnectionOrLogicSignal, Int], edges: Vector[(Int, Int)]) {
            require(edges.lastOption.forall(x => !events(x._2)._1.isInstanceOf[EndProcessing]))
            require(currentlyHandling == -1 || events(currentlyHandling)._1.isInstanceOf[StartProcessing])

            def handle(event: SnapshotEvent, idx: Int): State = event match {
              case StartProcessing(logicId, signal) => startHandling(logicId, signal, idx)
              case EndProcessing(logicId, signal)   => stopHandling()
              case TriggeredSignal(signal)          => triggered(signal, idx)
            }

            def startHandling(nextHandling: Int, signal: ConnectionOrLogicSignal, eventId: Int): State = {
              require(currentlyHandling == -1)
              val redeemed = waitingForExecution(signal)
              copy(
                currentlyHandling = eventId,
                waitingForExecution = waitingForExecution - signal,
                edges = edges :+ (eventId -> redeemed))
            }
            def stopHandling(): State = {
              require(currentlyHandling > -1)
              copy(currentlyHandling = -1)
            }
            def triggered(signal: ConnectionOrLogicSignal, idx: Int): State = {
              val newEdges = if (currentlyHandling == -1) edges else edges :+ (idx -> currentlyHandling)
              copy(
                waitingForExecution = waitingForExecution + (signal -> idx),
                edges = newEdges)
            }
          }

          events.foldLeft(State(-1, Map.empty, Vector.empty)) { (lastState, nextEvent) =>
            val (event, idx) = nextEvent
            lastState.handle(event, idx)
          }.edges
        }
        val causes = eventCausations()

        def signalString(signal: Signal): String = signal match {
          case SnapshotEvent.Pull     => s"Pull"
          case Push(data)             => s"Push(${data.toString.take(50)})"
          case SnapshotEvent.Complete => s"Complete"
          case Failure(cause)         => s"Fail($cause)"
          case SnapshotEvent.Cancel   => "Cancel"
          case AsyncCallback(data)    => s"AsyncCallback(${data.toString.take(50)})"
        }
        def csignalString(signal: ConnectionOrLogicSignal): String = signal match {
          case ConnectionSignal(connectionId, signal) =>
            val conn = snapshot.connections.find(_.id == connectionId).get
            val (origin, target) = signal match {
              case Pull | Cancel => (conn.in.index, conn.out.index)
              case _             => (conn.out.index, conn.in.index)
            }

            s"${signalString(signal)} ${logicString(target)} from ${logicString(origin)}"
          case LogicSignal(logicId, signal) => ???
        }
        def logicString(logicId: Int): String = snapshot.logics.find(_.index == logicId).get.label
        def eventString(evt: SnapshotEvent): String = evt match {
          case TriggeredSignal(signal)          => s"TRIGG ${csignalString(signal)}"
          case StartProcessing(logicId, signal) => s"START ${logicString(logicId)} ${csignalString(signal)}"
          case EndProcessing(logicId, signal)   => s"END  ${logicString(logicId)} ${csignalString(signal)}"
        }
        def connectOpenEnds(): Seq[(Int, Int)] = {
          val eventIds = events.filterNot(_._1.isInstanceOf[EndProcessing]).map(_._2)
          val withoutCause = eventIds.filter(e => !causes.exists(_._1 == e))
          val withoutEffect = eventIds.filter(e => !causes.exists(_._2 == e))

          withoutCause.flatMap { effect =>
            def timeOf(event: Int): Long = snapshot.history(event).timestampNanos
            val effectTime = timeOf(effect)
            def diffToEffect(cause: Int): Long = effectTime - timeOf(cause)

            val potentialCauses = withoutEffect.filter(diffToEffect(_) >= 0)

            if (potentialCauses.isEmpty) None
            else Some(effect -> potentialCauses.minBy(diffToEffect))
          }
        }

        sb.append("digraph causes {\n")
        events
          .filterNot(_._1.isInstanceOf[EndProcessing])
          .foreach {
            case (evt, idx) =>
              sb.append(s"""  E$idx [label="${eventString(evt)}"];\n""")
          }
        causes.foreach {
          case (effect, cause) =>
            sb.append(s"""  E$effect -> E$cause [];\n""")
        }
        connectOpenEnds().foreach {
          case (effect, cause) =>
            sb.append(s"""  E$effect -> E$cause [style=dotted, color=blue, label="time passes ..."];\n""")
        }

        sb.append("}\n")

        fos.write(sb.mkString.getBytes("utf8"))
        fos.close()
      }
    }

    try {
      //builder.append("\ndot format graph for deadlock analysis:\n")
      //builder.append("================================================================\n")
      builder.append("digraph waits {\n")

      for (i <- snapshot.logics.indices) {
        val logic = snapshot.logics(i)

        /*def activeFor(e: SnapshotEvent): Int = e match {
          case l: SnapshotEvent.LogicEvent       => l.logic.index
          case p: SnapshotEvent.Pull             => p.connection.out.index
          case p: SnapshotEvent.DownstreamFinish => p.connection.out.index
          case p: SnapshotEvent.Push             => p.connection.in.index
          case p: SnapshotEvent.UpstreamFinish   => p.connection.in.index
          case p: SnapshotEvent.UpstreamFailure  => p.connection.in.index
        }*/
        val (extraArgs: String, extraLabel: String) = ("", "")
        /*snapshot.lastEvent match {
            case Some(e: SnapshotEvent) if activeFor(e) == logic.index =>
              (""", color=blue, bgcolor="#cccccc"""", "")
            case _ => ("", "")
          }*/

        builder.append(s"""  N$i [label="${logic.label}$extraLabel"$extraArgs];""").append('\n')
      }

      for (connection <- snapshot.connections) {
        val inName = "N" + connection.in.index
        val outName = "N" + connection.out.index

        val extraArgs: String = ""
        /*snapshot.lastEvent match {
            case Some(e: ConnectionEvent) if e.connection.asInstanceOf[ConnectionSnapshotImpl].id == connection.asInstanceOf[ConnectionSnapshotImpl].id =>
              (e match {
                case SnapshotEvent.Pull(_)                => ", label=pull"
                case SnapshotEvent.Push(_, value)         => s""", label="push [${sanitize(value.toString)}]""""
                case SnapshotEvent.DownstreamFinish(_)    => ", label=cancel"
                case SnapshotEvent.UpstreamFinish(_)      => ", label=complete"
                case SnapshotEvent.UpstreamFailure(_, ex) => s""", label="fail [${sanitize(ex.toString)}]""""
              }) + ", color=blue, penwidth=2"
            case _ => ""
          }*/

        builder.append(s"  $outName -> $inName ")
        connection.state match {
          case ConnectionSnapshot.ShouldPull =>
            builder.append(s"[arrowhead=tee$extraArgs];")
          case ConnectionSnapshot.ShouldPush =>
            builder.append(s"[arrowhead=empty$extraArgs];")
          case ConnectionSnapshot.Pulling =>
            builder.append(s"[arrowtail=tee, arrowhead=empty $extraArgs];")
          case ConnectionSnapshot.Pushing =>
            builder.append(s"[arrowhead=normal$extraArgs];")
          case ConnectionSnapshot.Closed =>
            builder.append(s"[arrowhead=empty, style=dotted$extraArgs];")
          case _ =>
        }
        builder.append("\n")
      }

      def sanitize(str: String): String =
        (if (str.size > 50) str.take(50) + "..." else str)
          .replace('\"', '\'')

      /*snapshot.lastEvent match {
        case Some(e: SnapshotEvent.ConnectionEvent) =>
          val connection = e.connection
          val inName = "N" + connection.in.asInstanceOf[LogicSnapshotImpl].index
          val outName = "N" + connection.out.asInstanceOf[LogicSnapshotImpl].index

          val attrs: String = e match {
            case SnapshotEvent.Pull(_)                => "label=pull"
            case SnapshotEvent.Push(_, value)         => s"""label="push [${sanitize(value.toString)}]", dir=back"""
            case SnapshotEvent.DownstreamFinish(_)    => "label=cancel"
            case SnapshotEvent.UpstreamFinish(_)      => "label=complete, dir=back"
            case SnapshotEvent.UpstreamFailure(_, ex) => s"""label="fail [${sanitize(ex.toString)}]", dir=back"""
          }
          builder.append(s"  $inName -> $outName [color=green, penwidth=5, $attrs];\n")
        case _ =>
      }*/
      builder.append("}\n")
      //builder.append("}\n================================================================\n")
      //builder.append(s"// ${snapshot.queueStatus} (running=${snapshot.runningLogicsCount}, shutdown=${snapshot.stoppedLogics.mkString(",")})")
      builder.toString()
    } catch {
      case _: NoSuchElementException => builder.append("Not all logics has a stage listed, cannot create graph")
    }
  }

}
