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

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import static org.apache.camel.karavan.persistence.ProjectCacheEntity.TABLE_NAME;

/** Projects, project files and commit state serialized as JSONB. */
@Entity
@Table(name = TABLE_NAME, indexes = {
        @Index(name = "idx_project_type", columnList = "type"),
        @Index(name = "idx_project_last_update", columnList = "last_update")
})
public class ProjectCacheEntity extends KeyValueCacheEntity {

    public static final String TABLE_NAME = "project_state";
}
