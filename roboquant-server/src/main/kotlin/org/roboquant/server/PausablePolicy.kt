/*
 * Copyright 2020-2023 Neural Layer
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

package org.roboquant.server

import org.roboquant.brokers.Account
import org.roboquant.feeds.Event
import org.roboquant.orders.Order
import org.roboquant.policies.Policy
import org.roboquant.strategies.Signal

internal class PausablePolicy(private val policy: Policy, var pause: Boolean = false) : Policy by policy {

    override fun act(signals: List<Signal>, account: Account, event: Event): List<Order> {
        // Still invoke the policy so any state can be updated if required.
        val orders = policy.act(signals, account, event)
        return if (pause) emptyList() else orders
    }

}