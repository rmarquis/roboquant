/*
 * Copyright 2020-2024 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("WildcardImport", "MaxLineLength")

package org.roboquant.ibkr

import com.ib.client.*
import com.ib.client.Types.Action
import com.ib.controller.AccountSummaryTag
import org.roboquant.brokers.*
import org.roboquant.brokers.sim.execution.InternalAccount
import org.roboquant.common.*
import org.roboquant.feeds.Event
import org.roboquant.ibkr.IBKR.toAsset
import org.roboquant.ibkr.IBKR.toContract
import org.roboquant.orders.*
import org.roboquant.orders.OrderStatus
import java.math.BigDecimal
import java.time.Instant
import com.ib.client.Order as IBOrder
import com.ib.client.OrderState as IBOrderSate
import com.ib.client.OrderStatus as IBOrderStatus


/**
 * Use your Interactive Brokers account for trading. Can be used with live trading or paper trading accounts of
 * Interactive Brokers. It is highly recommended to start with a paper trading account and validate your strategy and
 * signalConverter extensively before moving to live trading.
 *
 * ## Use at your own risk, since there are no guarantees about the correct functioning of the roboquant software.
 *
 * @param configure additional configuration
 * @constructor
 */
class IBKRBroker(
    configure: IBKRConfig.() -> Unit = {}
) : Broker {

    private val config = IBKRConfig()
    private var initialized = false

    private val accountId: String?
    private var client: EClientSocket
    private var account = InternalAccount(Currency.USD)
    private var accountUpdateLock = Object()
    private var orderIds = mutableSetOf<Int>()
    private var nextOrderId = 0

    /**
     * ExchangeRates as provided during intialization of the account.
     */
    val exchangeRates = FixedExchangeRates(Currency.USD)

    private val logger = Logging.getLogger(IBKRBroker::class)

    init {
        config.configure()
        require(config.account.isBlank() || config.account.startsWith('D')) { "only paper trading is supported" }
        accountId = config.account.ifBlank { null }
        logger.info { "using account=$accountId" }
        val wrapper = Wrapper(logger)
        client = IBKR.connect(wrapper, config)
        Thread.sleep(2_000)
        client.reqCurrentTime()

        synchronized(accountUpdateLock) {
            client.reqAccountUpdates(true, accountId)
            accountUpdateLock.wait(IBKR.MAX_RESPONSE_TIME)
            if (! initialized) logger.warn { "not correctly initialized" }
        }

        sync()
    }


    /**
     * Disconnect roboquant from TWS or IB Gateway
     */
    fun disconnect() {
        client.reqAccountUpdates(false, accountId)
        Thread.sleep(1_000)
        IBKR.disconnect(client)
    }


    private fun IBOrder.log(contract: Contract) {
        logger.info {
            "placing order id=${orderId()} size=${totalQuantity()} type=${orderType()} contract=$contract"
        }
    }

    /**
     * Cancel an order
     */
    private fun cancelOrder(cancellation: Cancellation) {
        val id = cancellation.orderId
        logger.info("cancelling order with id $id")
        client.cancelOrder(id.toInt(), cancellation.tag)

    }

    /**
     * Place an order of type [SingleOrder]
     */
    private fun placeSingleOrder(order: SingleOrder) {
        val contract = order.asset.toContract()
        val ibOrder = createIBOrder(order)
        ibOrder.log(contract)
        val id = ibOrder.orderId()
        client.placeOrder(id, contract, ibOrder)
        account.orders.add(order)
    }

    override fun sync(event: Event?): Account {
        if (event != null) {
            if (event.time < Instant.now() - 1.hours) throw UnsupportedException("cannot place orders in the past")
        }
        val tags = "${AccountSummaryTag.BuyingPower}, ${AccountSummaryTag.TotalCashValue}"
        client.reqPositions()
        client.reqAllOpenOrders()
        client.reqAccountSummary(1, "All", tags)

        return account.toAccount()
    }

    /**
     * Place zero or more [instructions]
     *
     * @param instructions
     */
    override fun place(instructions: List<Instruction>) {
        // Sanity-check that you don't use this broker during back testing.


        for (order in instructions) {
            if (order is Order && order.id.isBlank()) order.id = nextOrderId++.toString()
            logger.info("received order=$order")
            when (order) {
                is Cancellation -> cancelOrder(order)
                is SingleOrder -> placeSingleOrder(order)
                is BracketOrder -> placeBracketOrder(order)
                else -> {
                    throw UnsupportedException("unsupported order type order=$order")
                }
            }

        }
    }

    /**
     * convert a roboquant [order] to an IBKR order.
     */
    private fun createIBOrder(order: SingleOrder): IBOrder {
        val result = IBOrder()
        with(result) {
            when (order) {
                is MarketOrder -> orderType(OrderType.MKT)
                is LimitOrder -> {
                    orderType(OrderType.LMT); lmtPrice(order.limit)
                }

                is StopOrder -> {
                    orderType(OrderType.STP); auxPrice(order.stop)
                }

                is StopLimitOrder -> {
                    orderType(OrderType.STP_LMT); lmtPrice(order.limit); auxPrice(order.stop)
                }

                is TrailOrder -> {
                    orderType(OrderType.TRAIL); trailingPercent(order.trailPercentage * 100.0)
                }

                else -> throw UnsupportedException("unsupported order type $order")
            }
        }

        val action = if (order.buy) Action.BUY else Action.SELL
        result.action(action)
        val qty = Decimal.get(order.size.toBigDecimal().abs())
        result.totalQuantity(qty)

        if (accountId != null) result.account(accountId)

        if (order.id.isBlank()) {
            order.id = nextOrderId++.toString()
        }
        result.orderId(order.id.toInt())
        orderIds.add(order.id.toInt())
        return result
    }


    private fun placeBracketOrder(order: BracketOrder) {
        val entry = createIBOrder(order.entry)
        val profit = createIBOrder(order.takeProfit)
        profit.parentId(entry.orderId())
        val loss = createIBOrder(order.stopLoss)
        loss.parentId(entry.orderId())
        loss.transmit(true)
        val orders = listOf(entry, profit, loss)
        val contract = order.entry.asset.toContract()

        for (o in orders) {
            o.log(contract)
            client.placeOrder(o.orderId(), contract, o)
        }
    }

    /**
     * Overwrite the default wrapper
     */
    private inner class Wrapper(logger: Logging.Logger) : BaseWrapper(logger) {

        val accountTags = mutableMapOf(
            AccountSummaryTag.TotalCashValue to Amount(Currency.USD, 0),
            AccountSummaryTag.BuyingPower to Amount(Currency.USD, 0),
        )

        /**
         * Convert an IBOrder to a roboquant Instruction.
         * This is only used during initial connection when retrieving any open orders linked to the account.
         */
        private fun toOrder(order: IBOrder, contract: Contract): Order {
            val asset = contract.toAsset()
            val qty = if (order.action() == Action.BUY) order.totalQuantity() else order.totalQuantity().negate()
            val size = Size(qty.value())
            val result = when (order.orderType()) {
                OrderType.MKT -> MarketOrder(asset, size)
                OrderType.LMT -> LimitOrder(asset, size, order.lmtPrice())
                OrderType.STP -> StopOrder(asset, size, order.auxPrice())
                OrderType.TRAIL -> TrailOrder(asset, size, order.trailingPercent() / 100.0)
                OrderType.STP_LMT -> StopLimitOrder(asset, size, order.auxPrice(), order.lmtPrice())
                else -> throw UnsupportedException("$order")
            }
            result.id = order.orderId().toString()
            return result
        }

        private fun toStatus(status: String): OrderStatus {
            return when (IBOrderStatus.valueOf(status)) {
                IBOrderStatus.Submitted -> OrderStatus.ACCEPTED
                IBOrderStatus.Cancelled -> OrderStatus.CANCELLED
                IBOrderStatus.Filled -> OrderStatus.COMPLETED
                IBOrderStatus.PreSubmitted -> OrderStatus.CREATED
                else -> OrderStatus.CREATED
            }
        }

        /**
         * What is the next valid IBKR orderID we can use
         */
        override fun nextValidId(orderId: Int) {
            logger.info("settting next valid orderId=$orderId")
            nextOrderId = orderId
        }


        override fun openOrder(orderId: Int, contract: Contract, order: IBOrder, orderState: IBOrderSate) {
            logger.debug { "orderId=$orderId asset=${contract.symbol()} qty=${order.totalQuantity()} status=${orderState.status}" }
            logger.trace { "$orderId $contract $order $orderState" }
            val openOrder = account.getOrder(orderId.toString())
            if (openOrder != null) {
                logger.info {"update order orderId=$orderId status=${orderState.status}" }
                if (orderState.completedStatus() == "true") {
                    val o = account.getOrder(openOrder.id)
                    if (o != null) {
                        account.updateOrder(o, Instant.parse(orderState.completedTime()), OrderStatus.COMPLETED)
                    }
                }
            } else if (orderId !in orderIds){
                logger.info { "existing order orderId=$orderId parentId=${order.parentId()} status=${orderState.status}" }
                // if (order.parentId() > 0) return // right now no support for open bracket orders
                val newOrder = toOrder(order, contract)
                account.orders.add(newOrder)
                val newStatus = toStatus(orderState.status)
                account.updateOrder(newOrder, Instant.now(), newStatus)
            }
        }


        override fun orderStatus(
            orderId: Int, status: String, filled: Decimal?,
            remaining: Decimal?, avgFillPrice: Double, permId: Int, parentId: Int,
            lastFillPrice: Double, clientId: Int, whyHeld: String?, mktCapPrice: Double
        ) {
            logger.info { "orderstatus oderId=$orderId status=$status filled=$filled" }
            val order = account.getOrder(orderId.toString())
            if (order != null) {
                val newStatus = toStatus(status)
                account.updateOrder(order, Instant.now(), newStatus)
                account.lastUpdate = Instant.now()
            } else {
                logger.warn { "Received orderStatus for unknown order with orderId=$orderId" }
            }
        }


        override fun openOrderEnd() {
            logger.info("openOrderEnd")
        }

        override fun accountDownloadEnd(accountName: String?) {
            logger.info("accountDownloadEnd accountName=$accountName")
            initialized = true
            synchronized(accountUpdateLock) {
                accountUpdateLock.notify()
            }
        }

        override fun updateAccountValue(key: String, value: String, currency: String?, accountName: String?) {
            logger.info { "updateAccountValue key=$key value=$value currency=$currency account=$accountName" }

            if (key == "AccountCode") require(value.startsWith('D')) {
                "currently only paper trading account is supported, found $value"
            }

            if (currency != null && "BASE" != currency) {
                val c = Currency.getInstance(currency)
                when (key) {
                    AccountSummaryTag.BuyingPower.name -> {
                        if (! initialized) {
                            account.baseCurrency = c
                            exchangeRates.baseCurrency = c
                        }
                        account.buyingPower = Amount(c, value.toDouble())

                    }
                    AccountSummaryTag.TotalCashValue.name -> account.cash.set(c, value.toDouble())
                    "ExchangeRate" -> {
                        exchangeRates.setRate(c,value.toDouble())
                    }
                }
            }
        }

        override fun updatePortfolio(
            contract: Contract,
            position: Decimal,
            marketPrice: Double,
            marketValue: Double,
            averageCost: Double,
            unrealizedPNL: Double,
            realizedPNL: Double,
            accountName: String?
        ) {
            logger.info { "updatePortfolio asset=${contract.symbol()} position=$position price=$marketPrice cost=$averageCost" }
            logger.trace { "updatePortfolio $contract $position $marketPrice $averageCost" }
            val asset = contract.toAsset()
            val size = Size(position.value())
            val p = Position(asset, size, averageCost, marketPrice, Instant.now())
            account.setPosition(p)
            account.lastUpdate = Instant.now()
        }

        override fun position(account: String?, contract: Contract?, pos: Decimal?, avgCost: Double) {
            logger.info { "$account, $contract, $pos, $avgCost" }
        }

        override fun positionEnd() {
            logger.info { "positionEnd" }
        }

        override fun updateAccountTime(timeStamp: String?) {
            logger.trace("updateAccountTime timestamp=$timeStamp")
        }


    }
}

