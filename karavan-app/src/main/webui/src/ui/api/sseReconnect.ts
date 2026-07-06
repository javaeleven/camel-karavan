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

/**
 * Shared reconnect policy for the SSE streams (notifications + log watch).
 *
 * fetch-event-source stops FOREVER on a graceful server close (it only retries
 * on errors), but our streams close gracefully all the time — a devmode
 * container stop ends its log stream, an app restart ends the notification
 * streams. Throwing RetriableError from onclose routes the close through the
 * error path, where onerror returns the next backoff delay to retry.
 */
export class RetriableError extends Error {
}

/** Non-retriable: bad auth / 4xx responses must stop the stream. */
export class FatalError extends Error {
}

/**
 * Capped exponential backoff: 1s, 2s, 4s, 8s, 8s, ...
 * IMPORTANT: reset on the first received MESSAGE, not on connection open — a
 * stream for a nonexistent container opens fine and dies instantly; resetting
 * on open turns the retry loop into a permanent ~1s hammer.
 */
export class SseBackoff {
    private attempt = 0;

    next(): number {
        const delay = Math.min(1000 * 2 ** this.attempt, 8000);
        this.attempt = Math.min(this.attempt + 1, 10);
        return delay;
    }

    reset() {
        this.attempt = 0;
    }
}

/**
 * Standard onerror handler: stop on fatal errors and on an aborted controller,
 * retry (with backoff) on everything else — network drops, proxy idle
 * timeouts, server restarts, graceful closes rethrown as RetriableError.
 */
export function retryOrRethrow(err: any, backoff: SseBackoff): number {
    if (err instanceof FatalError) {
        throw err;
    }
    return backoff.next();
}
