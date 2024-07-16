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

package org.roboquant.samples

import kotlinx.coroutines.runBlocking
import org.roboquant.avro.AvroFeed
import org.roboquant.brokers.sim.MarginAccount
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.*
import org.roboquant.feeds.csv.CSVConfig
import org.roboquant.feeds.csv.CSVFeed
import org.roboquant.feeds.csv.TimeParser
import org.roboquant.strategies.FlexConverter
import org.roboquant.run
import org.roboquant.runAsync
import org.roboquant.strategies.EMACrossover
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

internal class AvroSamples {

    @Test
    @Ignore
    internal fun generate_sp25() {
        val path = Path("/tmp/us")
        val path1 = path / "nasdaq stocks"
        val path2 =  path / "nyse stocks"

        val feed = CSVFeed(path1.toString(), CSVConfig.stooq())
        val tmp = CSVFeed(path2.toString(), CSVConfig.stooq())
        feed.merge(tmp)

        val avroFeed = AvroFeed("/tmp/sp25_v1.0.avro")

        val s = "MSFT,NVDA,AAPL,AMZN,META,GOOGL,GOOG,BRK.B,LLY,AVGO,JPM,XOM,TSLA,UNH,V,PG,MA,COST,JNJ,HD,MRK,NFLX,WMT,ABBV,CVX"
        val symbols = s.split(',').toTypedArray()
        assertEquals(25, symbols.size)

        val timeframe = Timeframe.fromYears(2020, 2024)
        avroFeed.record(
            feed,
            true,
            timeframe,
            assetFilter = AssetFilter.includeSymbols(*symbols)
        )

    }

    @Test
    @Ignore
    internal fun generate_all() {
        val path = Path("/tmp/us")
        val path1 = path / "nasdaq stocks"
        val path2 =  path / "nyse stocks"

        val feed = CSVFeed(path1.toString(), CSVConfig.stooq())
        val tmp = CSVFeed(path2.toString(), CSVConfig.stooq())
        feed.merge(tmp)

        val avroFeed = AvroFeed("/tmp/us_stocks_all.avro")

        avroFeed.record(
            feed,
            true
        )

    }

    @Test
    @Ignore
    internal fun generate_nasdaq() {
        val path = Path("/tmp/us")
        val path1 = path / "nasdaq stocks"

        val feed = CSVFeed(path1.toString(), CSVConfig.stooq())
        val avroFeed = AvroFeed("/tmp/nasdaq_stocks.avro")

        avroFeed.record(
            feed,
            true
        )

    }

    @Test
    @Ignore
    internal fun run_all() = runBlocking {
        val feed = AvroFeed("/tmp/us_stocks_all.avro")
        val jobs = ParallelJobs()
        feed.timeframe.split(10.years).forEach {
            jobs.add {
                val account = runAsync(feed, EMACrossover(), timeframe = it)
                println("$it => ${account.equityAmount()}")
            }
        }
        jobs.joinAll()
    }



}

