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
package org.apache.camel.karavan.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-user Git credentials, kept in system settings. Keyed by the authenticated
 * principal ({@link #username}, the login/email). Used to authenticate
 * per-project Git operations (fetch branches, clone, push, pull). Persisted as a
 * row in {@code access_state} (type = simple class name), same as AccessUser.
 * <p>
 * The token is stored as-is so it can be replayed to the Git remote; it is never
 * returned to the browser (the API masks it).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserGitConfig {

    String username;
    String gitUsername;
    String gitToken;

    public UserGitConfig copy() {
        return new UserGitConfig(username, gitUsername, gitToken);
    }
}
