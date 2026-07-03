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

/**
 * The Camel runtime a project is built/run with. Drives the application.properties
 * template, the build.sh packaging path (via CAMEL_RUNTIME) and jbang flags.
 */
public enum CamelRuntime {
    CAMEL_MAIN("camel-main"),
    QUARKUS("quarkus"),
    SPRING_BOOT("spring-boot");

    private final String value;

    CamelRuntime(String value) {
        this.value = value;
    }

    /**
     * Resolve a runtime from its value ("camel-main") or enum name ("CAMEL_MAIN"),
     * case-insensitively. Falls back to CAMEL_MAIN for null/blank/unknown.
     */
    public static CamelRuntime fromValue(String s) {
        if (s != null && !s.isBlank()) {
            for (CamelRuntime r : values()) {
                if (r.value.equalsIgnoreCase(s.trim()) || r.name().equalsIgnoreCase(s.trim())) {
                    return r;
                }
            }
        }
        return CAMEL_MAIN;
    }

    public String getValue() {
        return value;
    }
}
