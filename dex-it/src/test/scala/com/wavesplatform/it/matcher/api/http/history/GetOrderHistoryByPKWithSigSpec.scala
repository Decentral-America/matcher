package com.wavesplatform.it.matcher.api.http.history

import com.google.common.primitives.Longs
import sttp.model.StatusCode
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.api.http.entities.HttpOrderBookHistoryItem
import com.wavesplatform.dex.domain.bytes.codec.Base58
import com.wavesplatform.dex.domain.crypto
import com.wavesplatform.dex.domain.order.OrderType.BUY
import com.wavesplatform.dex.error.UserPublicKeyIsNotValid
import com.wavesplatform.dex.it.api.RawHttpChecks
import com.wavesplatform.dex.model.OrderStatus
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.it.matcher.api.http.toHttpOrderBookHistoryItem

class GetOrderHistoryByPKWithSigSpec extends MatcherSuiteBase with RawHttpChecks {

  override protected def dexInitialSuiteConfig: Config =
    ConfigFactory.parseString(
      s"""waves.dex {
         |  price-assets = [ "$UsdId", "DCC" ]
         |}""".stripMargin
    )

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx)
    dex1.start()
  }

  "GET /matcher/orders/{publicKey}" - {
    "should return all order history" in {

      withClue("empty history") {
        validate200Json(dex1.rawApi.getOrderHistoryByPKWithSig(alice)) should have size 0
      }

      val orders = List(
        mkOrder(alice, wavesUsdPair, BUY, 10.waves, 1.usd),
        mkOrder(alice, wavesUsdPair, BUY, 11.waves, 2.usd),
        mkOrder(alice, wavesUsdPair, BUY, 12.waves, 3.usd)
      )

      val historyActive = orders.map { order =>
        placeAndAwaitAtDex(order)
        toHttpOrderBookHistoryItem(order, OrderStatus.Accepted)
      }.sorted(HttpOrderBookHistoryItem.httpOrderBookHistoryItemOrdering)

      withClue("active only") {
        validate200Json(
          dex1.rawApi.getOrderHistoryByPKWithSig(alice, Some(true), Some(false))
        ) should matchTo(historyActive)
      }

      withClue("without params") {
        validate200Json(dex1.rawApi.getOrderHistoryByPKWithSig(alice)) should matchTo(historyActive)
      }

      val historyCancelled = orders.map { order =>
        cancelAndAwait(alice, order)
        toHttpOrderBookHistoryItem(order, OrderStatus.Cancelled(0, 0))
      }.sorted(HttpOrderBookHistoryItem.httpOrderBookHistoryItemOrdering)

      withClue("closed only") {
        validate200Json(
          dex1.rawApi.getOrderHistoryByPKWithSig(alice, Some(false), Some(true))
        ) should matchTo(historyCancelled)
      }
    }

    "should return an error if public key is not a correct base58 string" in {
      val ts = System.currentTimeMillis
      val sign = Base58.encode(crypto.sign(alice, alice.publicKey ++ Longs.toByteArray(ts)))

      validateMatcherError(
        dex1.rawApi.getOrderHistoryByPKWithSig("null", ts, sign),
        StatusCode.BadRequest,
        UserPublicKeyIsNotValid.code,
        "Provided public key is not correct, reason: Unable to decode base58: requirement failed: Wrong char 'l' in Base58 string 'null'"
      )
    }

    "should return an error if public key parameter has the different value of used in signature" in {
      val ts = System.currentTimeMillis
      val sign = Base58.encode(crypto.sign(alice, alice.publicKey ++ Longs.toByteArray(ts)))

      validateIncorrectSignature(dex1.rawApi.getOrderHistoryByPKWithSig(Base58.encode(bob.publicKey), ts, sign))
    }

    "should return an error if timestamp header has the different value of used in signature" in {
      val ts = System.currentTimeMillis
      val sign = Base58.encode(crypto.sign(alice, alice.publicKey ++ Longs.toByteArray(ts)))

      validateIncorrectSignature(dex1.rawApi.getOrderHistoryByPKWithSig(Base58.encode(alice.publicKey), ts + 1000, sign))
    }

    "should return an error with incorrect signature" in {
      validateIncorrectSignature(dex1.rawApi.getOrderHistoryByPKWithSig(Base58.encode(alice.publicKey), System.currentTimeMillis, "incorrect"))
    }
  }

}