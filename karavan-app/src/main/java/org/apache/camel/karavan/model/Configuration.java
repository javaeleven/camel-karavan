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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
public class Configuration {
    private String title;
    private String version;
    private String infrastructure;
    private boolean swarmMode;
    private String environment;
    private String platformSecretName;
    private String platformConfigName;
    private List<String> environments;
    private List<String> configFilenames;
    private List<Object> status;
    private Map<String, String> advanced;
    // Default Camel runtime offered when creating a project (camel-main/quarkus/spring-boot).
    private String defaultRuntime;

    public Configuration(String title, String version, String infrastructure, boolean swarmMode, String environment, String platformSecretName, String platformConfigName, List<String> environments, List<String> configFilenames, Map<String, String> advanced) {
        this.title = title;
        this.version = version;
        this.infrastructure = infrastructure;
        this.swarmMode = swarmMode;
        this.environment = environment;
        this.platformSecretName = platformSecretName;
        this.platformConfigName = platformConfigName;
        this.environments = environments;
        this.configFilenames = configFilenames;
        this.advanced = advanced;
    }
}
