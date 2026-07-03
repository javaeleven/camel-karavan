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
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Common shape of the three key/value JSONB state tables (project_state,
 * access_state, session_state): a {@code GroupedKey} primary key, the domain
 * object's simple class name as {@code type}, the object serialized to JSONB,
 * and audit timestamps. Fields are public because loaders read them directly.
 */
@MappedSuperclass
public abstract class KeyValueCacheEntity {

    @Id
    public String key;

    /** Simple class name of the serialized domain object (e.g. "ProjectFile"). */
    public String type;

    /** The domain object serialized to JSON. */
    @Column(columnDefinition = "jsonb")
    public String data;

    @UpdateTimestamp
    @Column(name = "last_update")
    public Instant lastUpdate;

    /** Audit: when the row was first inserted; never updated afterwards. */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    public Instant createdAt;
}
