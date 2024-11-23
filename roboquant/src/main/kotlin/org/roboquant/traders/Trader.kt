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

package org.roboquant.traders

import org.roboquant.brokers.Account
import org.roboquant.feeds.Event
import org.roboquant.orders.Instruction
import org.roboquant.strategies.Signal

/**
 * A trader is responsible for creating [Orders][Instruction], typically based on the [Signals][Signal] generated by a
 * strategy. Besides, turning signals into orders, a trader could also take care of:
 *
 * * signal conflicts: for example, receive both a SELL and BUY signal for the same asset at the same time
 * * order management: for example, how to deal with open orders
 * * portfolio construction: for example, re-balancing of the portfolio based on some pre-defined risk parameters
 * * risk management: for example, limit exposure to certain sectors
 *
 * Please note that a broker which receives the orders that a Trader created, might not support all the different
 * order types.
 */
interface Trader  {

    /**
     * Based on the received [signals], the latest state of the [account] and the last known [event], create zero or
     * more orders.
     *
     * @param signals the list of [signals][Signal] generated by the strategy
     * @param account the account state at a point in time
     * @param event the data available
     * @return a list of orders
     */
    fun createOrders(signals: List<Signal>, account: Account, event: Event): List<Instruction>


}
