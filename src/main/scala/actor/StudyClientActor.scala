package lila.ws

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ Behavior, PostStop }
import play.api.libs.json.JsValue

import ipc.*

object StudyClientActor:

  import ClientActor.*

  case class State(
      room: RoomActor.State,
      site: ClientActor.State = ClientActor.State()
  )

  def start(roomState: RoomActor.State, fromVersion: Option[SocketVersion])(
      deps: Deps
  ): Behavior[ClientMsg] =
    Behaviors.setup: ctx =>
      RoomActor.onStart(roomState, fromVersion, deps, ctx)
      apply(State(roomState), deps)

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def forward(payload: JsValue): Unit =
          lilaIn.study(LilaIn.TellRoomSri(state.room.room, LilaIn.TellSri(req.sri, req.user, payload)))

        def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] =

          case in: ClientIn =>
            clientInReceive(state.site, deps, in) match
              case None => Behaviors.same
              case Some(s) => apply(state.copy(site = s), deps)

          case ClientCtrl.Broom(oldSeconds) =>
            if state.site.lastPing < oldSeconds then Behaviors.stopped
            else
              keepAlive.study(state.room.room)
              Behaviors.same

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          case ClientOut.StudyForward(payload) =>
            forward(payload)
            Behaviors.same

          case anaMove: ClientOut.AnaMove =>
            clientIn(Chess(anaMove))
            forward(anaMove.payload)
            Behaviors.same

          case anaDrop: ClientOut.AnaDrop =>
            clientIn(Chess(anaDrop))
            forward(anaDrop.payload)
            Behaviors.same

          case ClientOut.VoiceChatPing =>
            deps.req.user.map { VoiceChat.respondToPing(state.room.room, _) }.foreach(clientIn)
            Behaviors.same

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if siteState == state.site then Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("study").increment()
            Behaviors.same

        RoomActor.receive(state.room, deps).lift(msg).fold(receive(msg)) { (newState, emit) =>
          emit.foreach(lilaIn.study)
          newState.fold(Behaviors.same[ClientMsg]): roomState =>
            apply(state.copy(room = roomState), deps)
        }
      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        RoomActor.onStop(state.room, deps, ctx)
        Behaviors.same
      }
