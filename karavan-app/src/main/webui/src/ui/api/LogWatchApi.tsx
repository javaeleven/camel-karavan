/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {fetchEventSource} from "@microsoft/fetch-event-source";
import {ProjectEventBus} from "@bus/ProjectEventBus";
import {getCurrentUser} from "@api/auth/AuthApi";
import {FatalError, RetriableError, retryOrRethrow, SseBackoff} from "@api/sseReconnect";

export class LogWatchApi {

    static async fetchData(type: 'container' | 'build' | 'none', podName: string, controller: AbortController) {
        const fetchData = async () => {
            const headers: Record<string, string> = {
                Accept: "text/event-stream",
            };
            const url = `/ui/logwatch/${type}/${podName}/${getCurrentUser()?.username ?? ""}`;
            const backoff = new SseBackoff();
            // BFF: authenticated by the session cookie (credentials:"include").
            await fetchEventSource(url, {
                method: "GET", headers: headers, signal: controller.signal, credentials: "include",
                async onopen(response) {
                    const ct = response.headers.get("content-type") || "";
                    if (response.ok && ct.toLowerCase().startsWith("text/event-stream")) {
                        // Fresh stream = fresh tail (the server replays the last 100
                        // lines). Reset the buffer so a reconnect after a container
                        // restart shows the NEW container's log, not stale lines.
                        ProjectEventBus.sendLog('set', '');
                        return;
                    }
                    if (response.status === 401) {
                        console.warn("SSE unauthorized: session missing/expired.");
                        throw new FatalError("unauthorized");
                    }
                    console.error("Unexpected SSE response", response.status, ct);
                    throw new FatalError(`bad-sse-response:${response.status}`);
                },
                onmessage(event) {
                    // A real message (incl. ping) proves the stream is healthy —
                    // only now reset the backoff (see sseReconnect.ts).
                    backoff.reset();
                    if (event.event !== 'ping') {
                        ProjectEventBus.sendLog('add', event.data);
                    }
                },
                onclose() {
                    // Graceful close = the container stopped (log follow ended) or the
                    // server restarted. Retry: when the container comes back (devmode
                    // restart keeps the same name) the stream reattaches automatically.
                    throw new RetriableError("log stream closed");
                },
                onerror(err) {
                    return retryOrRethrow(err, backoff);
                },
            });
        };
        return fetchData();
    }
}
