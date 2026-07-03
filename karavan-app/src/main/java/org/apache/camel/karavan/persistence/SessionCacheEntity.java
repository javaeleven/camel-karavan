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
package org.apache.camel.karavan.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

import static org.apache.camel.karavan.persistence.SessionCacheEntity.TABLE_NAME;

/** Machine sessions (build containers) serialized as JSONB, with an expiry for cleanup. */
@Entity
@Table(name = TABLE_NAME, indexes = {
        @Index(name = "idx_session_type", columnList = "type"),
        @Index(name = "idx_session_expiry", columnList = "expiry")
})
public class SessionCacheEntity extends KeyValueCacheEntity {

    public static final String TABLE_NAME = "session_state";

    /** The exact time this session expires (drives SessionCleanupService). */
    @Column(name = "expiry")
    public Instant expiry;
}
