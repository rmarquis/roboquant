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

package org.roboquant.server.routes

import io.ktor.http.*
import io.ktor.server.html.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import kotlinx.html.*
import org.roboquant.server.*
import java.time.temporal.ChronoUnit



private fun FlowContent.state(run: RunInfo, pause: Boolean) {
    when {
        run.done -> p(classes = "text-success") { +"done" }
        pause -> p(classes = "text-warning") {+ "paused" }
        else ->  p(classes = "text-success") { +"running" }
    }
}

private fun TABLE.tableHeader(vararg headers: String) {
    thead {
        tr {
            for (header in headers) th(scope = ThScope.col) { +header }
        }
    }
}

@Suppress("LongMethod")
private fun FlowContent.runTable() {
    table(classes = "table text-end my-5") {
        caption {
            +"Runs"
        }
        tableHeader("run", "state", "timeframe", "events", "instructions", "last update", "actions")
        tbody {
            runs.forEach { (run, info) ->
                val strategy = info.strategy
                tr {
                    td { +run }
                    td { state(info, strategy.pause) }
                    td { +info.timeframe.toPrettyString() }
                    td {
                        +"total = ${strategy.totalEvents}"
                        br
                        +"empty = ${strategy.emptyEvents}"
                        br
                        +"actions = ${strategy.totalActions}"
                    }
                    td { +strategy.totalInstructions.toString() }
                    td { +strategy.lastUpdate.truncatedTo(ChronoUnit.SECONDS).toString() }
                    td {
                        a(href = "/run/$run") { +"details" }
                        if (! info.done) {
                            br
                            a(href = "/?action=pause&run=$run") { +"pause" }
                            br
                            a(href = "/?action=resume&run=$run") { +"resume" }
                        }
                    }
                }
            }
        }

    }
}


private fun FlowContent.stopButton() {

    div(classes = "row justify-content-md-center mt-5") {
        button(classes = "btn btn-danger btn-lg col-lg-2") {
            hxGet = "/?action=stop"
            hxConfirm = "Are you sure you want to stop the server?"
            +"Stop Server"
        }
    }
}

internal fun Route.overview() {
    get("/") {
        val params = call.request.queryParameters

        if (params.contains("action")) {
            val action = params.getOrFail("action")
            if (action == "stop") {
                // Always exit, so no triggering of shutdown sequence
                logger.info { "web server is manually stopped" }
                Runtime.getRuntime().halt(1)
            }

            val run = params.getOrFail("run")
            val strategy = runs.getValue(run).strategy

            when (action) {
                    "pause" -> strategy.pause = true
                    "resume" -> strategy.pause = false
            }

        }

        call.respondHtml(HttpStatusCode.OK) {
            page("Overview") {
                runTable()
                stopButton()
            }
        }
    }
}
