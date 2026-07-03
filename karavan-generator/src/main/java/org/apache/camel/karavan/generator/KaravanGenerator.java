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
package org.apache.camel.karavan.generator;

import java.nio.file.Paths;

@lombok.extern.slf4j.Slf4j
public final class KaravanGenerator {

    // Generation targets (metadata + generated TS are written into these sibling
    // module dirs; the generator must run from the repo root).
    static final String TARGET_CORE = "karavan-core/test";
    static final String TARGET_APP = "karavan-app/src/main/resources";
    static final String TARGET_VSCODE = "karavan-vscode";


    public static void main(String[] args) throws Exception {
        String rootPath = args.length > 0 ? args[0] : "";
        boolean all = args.length == 0;
        String[] paths = all
                ? new String[] {TARGET_CORE, TARGET_APP, TARGET_VSCODE}
                : new String[] {TARGET_CORE, TARGET_APP};
        log.info("Generating Root Path: {}", rootPath);
        for (String path : paths) {
            log.info("    Generating Path: {}", path);
            AbstractGenerator.clearDirectory(Paths.get(path + "/metadata").toFile());
        }
        log.info("Generating Camel Definitions: {}", rootPath);
        CamelDefinitionGenerator.generate(rootPath);
        CamelDefinitionApiGenerator.generate(rootPath);
        CamelDefinitionYamlStepGenerator.generate(rootPath);
        CamelMetadataGenerator.generate(rootPath);
        KameletGenerator.generate(rootPath, paths);
        CamelComponentsGenerator.generate(rootPath, paths);
        CamelSpiBeanGenerator.generate(rootPath, paths);
        System.exit(0);
    }

}
