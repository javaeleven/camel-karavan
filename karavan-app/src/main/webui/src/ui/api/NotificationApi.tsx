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

import {EventStreamContentType, fetchEventSource} from "@microsoft/fetch-event-source";
import {EventSourceMessage} from "@microsoft/fetch-event-source/lib/cjs/parse";
import {KaravanEvent, NotificationEventBus} from "@services/NotificationService";
import {getCurrentUser} from "@api/auth/AuthApi";
import {FatalError, RetriableError, retryOrRethrow, SseBackoff} from "@api/sseReconnect";

export class NotificationApi {

    static getKaravanEvent (ev: EventSourceMessage, type: 'system' | 'user') {
        const eventParts = ev.event?.split(':');
        const event = eventParts?.length > 1 ? eventParts[0] : undefined;
        const className = eventParts?.length > 1 ? eventParts[1] : undefined;
        return new KaravanEvent({id: ev.id, event: event, type: type, className: className, data: JSON.parse(ev.data)});
    }

    static onSystemMessage (ev: EventSourceMessage) {
        const ke = NotificationApi.getKaravanEvent(ev, 'system');
        NotificationEventBus.sendEvent(ke);
    }

    static onUserMessage (ev: EventSourceMessage) {
        const ke = NotificationApi.getKaravanEvent(ev, 'user');
        NotificationEventBus.sendEvent(ke);
    }

    static async notification(controller: AbortController) {
        const fetchData = async () => {
            // BFF: SSE is authenticated by the session cookie (credentials:"include").
            const headers: any = { Accept: "text/event-stream" };
            if (getCurrentUser()) {
                NotificationApi.fetch('/ui/notification/system/' + getCurrentUser()?.username, controller, headers,
                    ev => NotificationApi.onSystemMessage(ev),
                    // Events sent while disconnected are lost — after the system
                    // stream reconnects, tell the app to resync its state.
                    () => NotificationEventBus.sendEvent(new KaravanEvent(
                        {id: '', event: 'reconnected', type: 'system', className: 'System', data: {}})));
                NotificationApi.fetch('/ui/notification/user/' + getCurrentUser()?.username, controller, headers,
                    ev => NotificationApi.onUserMessage(ev));
            }
        };
        return fetchData();
    };

    static async fetch(input: string, controller: AbortController, headers: any,
                       onmessage: (ev: EventSourceMessage) => void, onReconnect?: () => void) {
        const backoff = new SseBackoff();
        let hadConnection = false;
        fetchEventSource(input, {
            method: "GET",
            headers: headers,
            signal: controller.signal,
            credentials: "include",
            async onopen(response) {
                if (response.ok && response.headers.get('content-type') === EventStreamContentType) {
                    backoff.reset();
                    if (hadConnection) {
                        onReconnect?.();
                    }
                    hadConnection = true;
                    return; // everything's good
                } else if (response.status === 401) {
                    console.warn("SSE unauthorized: session missing/expired.");
                    throw new FatalError("unauthorized");
                } else if (response.status >= 400 && response.status < 500 && response.status !== 429) {
                    // client-side errors are non-retriable
                    console.error("Server side error ", response);
                    throw new FatalError(`bad-sse-response:${response.status}`);
                } else {
                    // 5xx / proxy hiccup: retriable
                    throw new RetriableError(`sse-response:${response.status}`);
                }
            },
            onmessage(event) {
                if (event.event !== 'ping') {
                    onmessage(event);
                }
            },
            onclose() {
                // Graceful close (server restart/redeploy): reconnect with backoff
                // instead of leaving the app without live updates until a reload.
                throw new RetriableError("notification stream closed");
            },
            onerror(err) {
                return retryOrRethrow(err, backoff);
            },
        });
    }
}
