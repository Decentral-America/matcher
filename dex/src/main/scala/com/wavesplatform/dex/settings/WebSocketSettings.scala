package com.wavesplatform.dex.settings

import com.wavesplatform.dex.api.ws.actors.WsHandlerActor
import net.ceedubs.ficus.readers.NameMapper

import scala.concurrent.duration.FiniteDuration

final case class WebSocketSettings(messagesInterval: FiniteDuration, webSocketHandler: WsHandlerActor.Settings)

object WebSocketSettings {

  implicit val chosenCase: NameMapper = MatcherSettings.chosenCase
}
