package com.wavesplatform.dex.it.api.dex

import cats.tagless._
import com.typesafe.config.Config
import com.wavesplatform.dex.api.http.entities._
import com.wavesplatform.dex.domain.account.{Address, KeyPair, PublicKey}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.order.Order
import im.mak.waves.transactions.ExchangeTransaction

@finalAlg
@autoFunctorK
trait DexApi[F[_]] {
  def publicKey: F[HttpMatcherPublicKey]

  def getReservedBalance(publicKey: String, timestamp: Long, signature: String): F[HttpBalance]
  def getReservedBalance(of: KeyPair, timestamp: Long = System.currentTimeMillis): F[HttpBalance]
  def getReservedBalance(publicKey: String, headers: Map[String, String]): F[HttpBalance]
  def getReservedBalanceWithApiKey(of: KeyPair, xUserPublicKey: Option[PublicKey] = None): F[HttpBalance]

  def getTradableBalance(address: String, amountAsset: String, priceAsset: String): F[HttpBalance]
  def getTradableBalance(of: KeyPair, assetPair: AssetPair): F[HttpBalance]

  def place(order: Order): F[HttpSuccessfulPlace]
  def placeMarket(order: Order): F[HttpSuccessfulPlace]

  def cancel(owner: KeyPair, order: Order): F[HttpSuccessfulSingleCancel] = cancel(owner, order.assetPair, order.id())
  def cancel(owner: KeyPair, assetPair: AssetPair, id: Order.Id): F[HttpSuccessfulSingleCancel]

  def cancelWithApiKey(order: Order, xUserPublicKey: Option[PublicKey] = None): F[HttpSuccessfulSingleCancel] =
    cancelWithApiKey(order.id(), xUserPublicKey)

  def cancelWithApiKey(id: Order.Id, xUserPublicKey: Option[PublicKey]): F[HttpSuccessfulSingleCancel]

  def cancelAll(owner: KeyPair, timestamp: Long = System.currentTimeMillis): F[HttpSuccessfulBatchCancel]

  def cancelAllByPair(
    owner: KeyPair,
    assetPair: AssetPair,
    timestamp: Long = System.currentTimeMillis
  ): F[HttpSuccessfulBatchCancel]

  def cancelAllByIdsWithApiKey(
    owner: Address,
    orderIds: Set[Order.Id],
    xUserPublicKey: Option[PublicKey] = None
  ): F[HttpSuccessfulBatchCancel]

  def getOrderStatus(order: Order): F[HttpOrderStatus] = getOrderStatus(order.assetPair, order.id())

  def getOrderStatus(assetPair: AssetPair, id: Order.Id): F[HttpOrderStatus] =
    getOrderStatus(assetPair.amountAssetStr, assetPair.priceAssetStr, id.toString)

  def getOrderStatus(amountAsset: String, priceAsset: String, id: String): F[HttpOrderStatus]

  def getOrderStatusInfoById(
    address: String,
    orderId: String,
    headers: Map[String, String] = Map.empty
  ): F[HttpOrderBookHistoryItem]

  def getOrderStatusInfoByIdWithApiKey(
    owner: Address,
    orderId: Order.Id,
    xUserPublicKey: Option[PublicKey]
  ): F[HttpOrderBookHistoryItem]

  def getOrderStatusInfoByIdWithSignature(publicKey: String, orderId: String, timestamp: Long, signature: String): F[HttpOrderBookHistoryItem]

  def getOrderStatusInfoByIdWithSignature(publicKey: String, orderId: String, headers: Map[String, String]): F[HttpOrderBookHistoryItem]

  def getOrderStatusInfoByIdWithSignature(
    owner: KeyPair,
    order: Order,
    timestamp: Long = System.currentTimeMillis
  ): F[HttpOrderBookHistoryItem] =
    getOrderStatusInfoByIdWithSignature(owner, order.id(), timestamp)

  def getOrderStatusInfoByIdWithSignature(
    owner: KeyPair,
    orderId: Order.Id,
    timestamp: Long
  ): F[HttpOrderBookHistoryItem]

  def getTransactionsByOrder(orderId: String): F[List[ExchangeTransaction]] = getTransactionsByOrder(orderId)

  def getTransactionsByOrder(order: Order): F[List[ExchangeTransaction]] = getTransactionsByOrder(order.id())

  def getTransactionsByOrder(id: Order.Id): F[List[ExchangeTransaction]]

  /**
   * param @activeOnly Server treats this parameter as false if it wasn't specified
   */
  def orderHistory(
    owner: KeyPair,
    activeOnly: Option[Boolean] = None,
    closedOnly: Option[Boolean] = None,
    timestamp: Long = System.currentTimeMillis
  ): F[List[HttpOrderBookHistoryItem]]

  /**
   * param @activeOnly Server treats this parameter as true if it wasn't specified
   */
  def orderHistoryWithApiKey(
    owner: Address,
    activeOnly: Option[Boolean] = None,
    closedOnly: Option[Boolean] = None,
    xUserPublicKey: Option[PublicKey] = None
  ): F[List[HttpOrderBookHistoryItem]]

  /**
   * param @activeOnly Server treats this parameter as false if it wasn't specified
   */
  def orderHistoryByPair(
    owner: KeyPair,
    assetPair: AssetPair,
    activeOnly: Option[Boolean] = None,
    closedOnly: Option[Boolean] = None,
    timestamp: Long = System.currentTimeMillis
  ): F[List[HttpOrderBookHistoryItem]]

  def getOrderBooks: F[HttpTradingMarkets]

  def getOrderBook(amountAsset: String, priceAsset: String): F[HttpV0OrderBook]
  def getOrderBook(assetPair: AssetPair): F[HttpV0OrderBook]
  def getOrderBook(assetPair: AssetPair, depth: String): F[HttpV0OrderBook]
  def getOrderBook(assetPair: AssetPair, depth: Int): F[HttpV0OrderBook]

  def getOrderBookInfo(assetPair: AssetPair): F[HttpOrderBookInfo]
  def getOrderBookInfo(amountAsset: String, priceAsset: String): F[HttpOrderBookInfo]

  def getOrderBookStatus(amountAsset: String, priceAsset: String): F[HttpOrderBookStatus]
  def getOrderBookStatus(assetPair: AssetPair): F[HttpOrderBookStatus]

  def deleteOrderBook(assetPair: AssetPair): F[HttpMessage]

  def upsertRate(asset: Asset, rate: Double): F[HttpMessage]
  def deleteRate(asset: Asset): F[HttpMessage]
  def rates: F[HttpRates]

  def currentOffset: F[HttpOffset]
  def lastOffset: F[HttpOffset]
  def oldestSnapshotOffset: F[HttpOffset]
  def allSnapshotOffsets: F[HttpSnapshotOffsets]
  def saveSnapshots: F[HttpMessage]

  def settings: F[HttpMatcherPublicSettings]
  def config: F[Config]

  def wsConnections: F[HttpWebSocketConnections]
  def closeWsConnections(oldestNumber: Int): F[HttpMessage]
}

object DexApi {} // Without this line we have java.lang.NoClassDefFoundError: com/wavesplatform/dex/it/dex/DexApi$
