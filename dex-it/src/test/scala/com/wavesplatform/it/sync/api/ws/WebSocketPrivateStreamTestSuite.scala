package com.wavesplatform.it.sync.api.ws

import cats.syntax.option._
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.api.websockets.{WsOrder, _}
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.order.Order
import com.wavesplatform.dex.domain.order.OrderType.{BUY, SELL}
import com.wavesplatform.dex.error.ErrorFormatterContext
import com.wavesplatform.dex.it.api.websockets.{HasWebSockets, WsAuthenticatedConnection}
import com.wavesplatform.dex.it.docker.DexContainer
import com.wavesplatform.dex.model.{LimitOrder, MarketOrder, OrderStatus}
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.wavesj.transactions.IssueTransaction

class WebSocketPrivateStreamTestSuite extends MatcherSuiteBase with HasWebSockets {
  private implicit val efc: ErrorFormatterContext = assetDecimalsMap.apply

  override protected val dexInitialSuiteConfig: Config = ConfigFactory.parseString(s"""waves.dex.price-assets = [ "$UsdId", "$BtcId", "WAVES" ]""")

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueBtcTx, IssueUsdTx)
    dex1.start()
    dex1.api.upsertRate(usd, 1)
  }

  override def afterEach(): Unit = dex1.api.cancelAll(alice)

  def placeAndWaitInStream(c: WsAuthenticatedConnection, o: Order): Unit = {
    val orders = c.getAllOrders
    placeAndAwaitAtDex(o)
    eventually {
      c.getAllOrders should have size orders.size + 1
    }
  }

  def waitForConnectionEstablished(c: WsAuthenticatedConnection): WsAuthenticatedConnection = {
    eventually {
      c.getAllBalances.size should be > 0
    }
    c
  }

  def mkWsConnection(account: KeyPair, method: String, dex: DexContainer = dex1): WsAuthenticatedConnection = {
    val c = method match {
      case "Signature" => mkWsAuthenticatedConnection(account, dex)
      case _           => mkWsAuthenticatedConnectionViaApiKey(account, dex)
    }
    waitForConnectionEstablished(c)
  }

  for (method <- Seq("Signature", "Api-Key")) s"should send account updates to authenticated (via $method) user" - {

    "when account is empty" in {
      val wsac = mkWsConnection(mkKeyPair("Test"), method)

      wsac.getAllBalances should have size 1
      wsac.getAllOrders should have size 0
    }

    "when user place and cancel limit order" in {
      val acc = mkAccountWithBalance(150.usd -> usd)
      val wsc = mkWsConnection(acc, method)

      val bo1 = mkOrderDP(acc, wavesUsdPair, BUY, 100.waves, 1.0)
      val bo2 = mkOrderDP(acc, wavesUsdPair, BUY, 10.waves, 1.0, feeAsset = usd, matcherFee = 30)

      placeAndWaitInStream(wsc, bo1)
      placeAndWaitInStream(wsc, bo2)

      eventually {
        wsc.getAllBalances.size should be >= 3
      }
      wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 150.0, reserved = 0.0))
      wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 0.0, reserved = 0.0))
      wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 39.7, reserved = 110.3))

      wsc.getAllOrders.distinct should matchTo(
        Seq(
          WsOrder.fromDomain(LimitOrder(bo1), OrderStatus.Accepted),
          WsOrder.fromDomain(LimitOrder(bo2), OrderStatus.Accepted)
        )
      )

      wsc.clearMessagesBuffer()

      cancelAndAwait(acc, bo1)

      eventually {
        wsc.getAllBalances.size should be >= 1
      }
      wsc.getAllBalances should contain(usd -> WsBalances(tradable = 139.7, reserved = 10.3))
      wsc.getAllOrders should contain(WsOrder(bo1.id(), status = OrderStatus.Cancelled.name.some))

      cancelAndAwait(acc, bo2)

      eventually {
        wsc.getAllOrders should contain(WsOrder(bo2.id(), status = OrderStatus.Cancelled.name.some))
        wsc.getAllBalances should contain(usd -> WsBalances(tradable = 150.0, reserved = 0.0))
      }
    }

    "when user place and fill market order" in {
      val acc = mkAccountWithBalance(51.003.waves -> Waves)
      val wsc = mkWsConnection(acc, method)
      val smo = mkOrderDP(acc, wavesUsdPair, SELL, 50.waves, 1.0)

      placeAndAwaitAtDex(mkOrderDP(alice, wavesUsdPair, BUY, 15.waves, 1.2))
      placeAndAwaitAtDex(mkOrderDP(alice, wavesUsdPair, BUY, 25.waves, 1.1))
      placeAndAwaitAtDex(mkOrderDP(alice, wavesUsdPair, BUY, 40.waves, 1.0))

      dex1.api.placeMarket(smo)
      waitForOrderAtNode(smo)

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 51.003, reserved = 0.0))
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 1.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 55.5, reserved = 0.0))
      }

      wsc.getAllOrders.distinct should matchTo(
        Seq(
          WsOrder.fromDomain(MarketOrder(smo, 1.waves), status = OrderStatus.Filled(50.0.waves, 0.003.waves), 50.0, 0.003, 1.11)
        )
      )
    }

    "when user order fully filled with another one" in {
      val acc = mkAccountWithBalance(10.usd -> usd)
      val wsc = mkWsConnection(acc, method)
      val bo1 = mkOrderDP(acc, wavesUsdPair, BUY, 10.waves, 1.0)

      placeAndWaitInStream(wsc, bo1)
      placeAndAwaitAtNode(mkOrderDP(alice, wavesUsdPair, SELL, 10.waves, 1.0))

      eventually {
        wsc.getAllOrders.distinct should have size 2
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 0.0, reserved = 0.0))
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 9.997, reserved = 0.0))
      }

      wsc.getAllOrders.distinct should matchTo(
        Seq(
          WsOrder.fromDomain(LimitOrder(bo1), OrderStatus.Accepted),
          WsOrder.fromDomain(LimitOrder(bo1), status = OrderStatus.Filled(10.waves, 0.003.waves), 10.0, 0.003, 1.0)
        )
      )
    }

    "when user's order partially filled with another one" in {
      val acc = mkAccountWithBalance(10.usd -> usd)
      val wsc = mkWsConnection(acc, method)
      val bo1 = mkOrderDP(acc, wavesUsdPair, BUY, 10.waves, 1.0)

      placeAndWaitInStream(wsc, bo1)
      placeAndAwaitAtNode(mkOrderDP(alice, wavesUsdPair, SELL, 5.waves, 1.0))

      eventually {
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 0.0, reserved = 5.0))
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 4.9985, reserved = 0.0))
      }

      wsc.getAllOrders.distinct should matchTo(
        Seq(
          WsOrder.fromDomain(LimitOrder(bo1), OrderStatus.Accepted),
          WsOrder.fromDomain(LimitOrder(bo1), status = OrderStatus.PartiallyFilled(5.waves, 0.0015.waves), 5.0, 0.0015, 1.0)
        )
      )

      dex1.api.cancelAll(acc)
    }

    "when user make a transfer" in {
      val acc = mkAccountWithBalance(10.waves -> Waves, 10.usd -> usd)
      val wsc = mkWsConnection(acc, method)

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 10.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 10.0, reserved = 0.0))
      }
      wsc.clearMessagesBuffer()

      broadcastAndAwait(mkTransfer(acc, alice.toAddress, 2.usd, usd, feeAmount = 1.waves))

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 9.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 8.0, reserved = 0.0))
      }
    }

    "user had issued a new asset after the connection already established" in {
      val acc                       = mkAccountWithBalance(10.waves -> Waves)
      val wsc                       = mkWsConnection(acc, method)
      val txIssue: IssueTransaction = mkIssue(acc, "testAsset", 1000.waves, 8)

      broadcastAndAwait(txIssue)

      eventually {
        wsc.getAllBalances should contain(Waves                      -> WsBalances(tradable = 9.0, reserved = 0.0))
        wsc.getAllBalances should contain(IssuedAsset(txIssue.getId) -> WsBalances(tradable = 1000.0, reserved = 0.0))
      }
    }

    "user had issued a new asset before establishing the connection" in {
      val acc                       = mkAccountWithBalance(10.waves -> Waves)
      val txIssue: IssueTransaction = mkIssue(acc, "testAsset", 1000.waves, 8)

      broadcastAndAwait(txIssue)

      val wsc = mkWsConnection(acc, method)

      eventually {
        wsc.getAllBalances should contain(Waves                      -> WsBalances(tradable = 9.0, reserved = 0.0))
        wsc.getAllBalances should contain(IssuedAsset(txIssue.getId) -> WsBalances(tradable = 1000.0, reserved = 0.0))
      }
    }

    "user burnt part of the asset amount" in {
      val acc = mkAccountWithBalance(10.waves -> Waves, 20.usd -> usd)
      val wsc = mkWsConnection(acc, method)

      wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 10.0, reserved = 0.0))
      wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 20.0, reserved = 0.0))

      broadcastAndAwait(mkBurn(acc, usd, 10.usd))

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 9.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 10.0, reserved = 0.0))
      }
    }

    "user burnt all of the asset amount" in {
      val acc = mkAccountWithBalance(10.waves -> Waves, 20.usd -> usd)
      val wsc = mkWsConnection(acc, method)

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 10.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 20.0, reserved = 0.0))
      }

      wsc.clearMessagesBuffer()

      broadcastAndAwait(mkBurn(acc, usd, 20.usd))

      eventually {
        wsc.getAllBalances should contain(Waves -> WsBalances(tradable = 9.0, reserved = 0.0))
        wsc.getAllBalances should contain(usd   -> WsBalances(tradable = 0.0, reserved = 0.0))
      }
    }
  }

  "Second connection should get the actual data" in {
    val acc = mkAccountWithBalance(500.usd -> usd)
    val wsc = mkWsAuthenticatedConnection(acc, dex1)

    val bo1 = mkOrderDP(acc, wavesUsdPair, BUY, 100.waves, 1.0)
    val bo2 = mkOrderDP(acc, wavesUsdPair, BUY, 100.waves, 1.0)

    placeAndWaitInStream(wsc, bo1)
    placeAndWaitInStream(wsc, bo2)

    val wsc1 = mkWsAuthenticatedConnection(acc, dex1)

    eventually { wsc1.getAllBalances.distinct should have size 1 }
    wsc1.getAllBalances.head should be(wsc.getAllBalances.last)
    wsc1.getAllOrders.distinct.sortBy(_.timestamp) should matchTo(
      Seq(
        WsOrder.fromDomain(LimitOrder(bo1), OrderStatus.Accepted),
        WsOrder.fromDomain(LimitOrder(bo2), OrderStatus.Accepted)
      )
    )
  }
}