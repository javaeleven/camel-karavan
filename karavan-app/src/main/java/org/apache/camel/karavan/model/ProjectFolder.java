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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Getter
@Setter
@ToString(of = {"projectId", "name", "lastUpdate", "type"})
public class ProjectFolder {

    @NotBlank(message = "projectId is required")
    @Size(max = 100, message = "projectId is too long")
    @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "projectId must be lowercase alphanumeric with dashes")
    String projectId;
    @NotBlank(message = "project name is required")
    @Size(max = 255, message = "project name is too long")
    String name;
    Long lastUpdate = 0L;
    Type type;
    // Per-project Git remote. When set, this project is committed/pushed to its
    // own repository+branch (selected via the "Fetch branches" action in the UI)
    // using the owning user's credentials from System -> Git. Null => the project
    // is local-only (no Git operations). gitOwner is the username that configured
    // the remote: only that user may configure/push/pull/build it.
    String gitRepository;
    String gitBranch;
    String gitOwner;
    // Camel runtime the project is built/run with: "camel-main" (default), "quarkus"
    // or "spring-boot" (see KaravanConstants.CamelRuntime). Drives which application
    // properties template is generated and which build path build.sh takes (via the
    // CAMEL_RUNTIME env injected into the build pod/container).
    String runtime = CamelRuntime.CAMEL_MAIN.getValue();
    // Audit: who created the project and when (epoch millis). createdBy also feeds
    // the project write-access check (creator or admin manage a project; null =
    // legacy/imported project, treated as unrestricted for compatibility).
    String createdBy;
    Long createdAt;
    // Users (besides the creator) allowed to manage this project.
    List<String> assignedUsers;
    public ProjectFolder(String projectId, String name, Long lastUpdate, Type type) {
        this.projectId = projectId;
        this.name = name;
        this.lastUpdate = lastUpdate;
        this.type = type;
    }

    public ProjectFolder(String projectId, String name, Long lastUpdate) {
        this.projectId = projectId;
        this.name = name;
        this.lastUpdate = lastUpdate;
        this.type = Arrays.stream(Type.values()).anyMatch(t -> t.name().equals(projectId)) ? Type.valueOf(projectId) : Type.integration;
    }

    public ProjectFolder(String projectId, String name) {
        this.projectId = projectId;
        this.name = name;
        this.lastUpdate = Instant.now().getEpochSecond() * 1000L;
        this.type = Arrays.stream(Type.values()).anyMatch(t -> t.name().equals(projectId)) ? Type.valueOf(projectId) : Type.integration;
    }

    public ProjectFolder() {
        this.type = Type.integration;
    }

    public static List<String> getBuildInNames() {
        return List.of(
                Type.configuration.name(),
                Type.kamelets.name(),
                Type.templates.name(),
                Type.contracts.name(),
                Type.documentation.name(),
                Type.backlog.name()
        );
    }

    public ProjectFolder copy() {
        ProjectFolder c = new ProjectFolder(projectId, name, lastUpdate, type);
        c.gitRepository = gitRepository;
        c.gitBranch = gitBranch;
        c.gitOwner = gitOwner;
        c.runtime = runtime;
        c.createdBy = createdBy;
        c.createdAt = createdAt;
        c.assignedUsers = assignedUsers;
        return c;
    }

    // Manual getter (kept over Lombok's): falls back to camel-main for legacy data.
    public String getRuntime() {
        return runtime != null ? runtime : CamelRuntime.CAMEL_MAIN.getValue();
    }

    public enum Type {
        templates,
        kamelets,
        configuration,
        documentation,
        contracts,
        services,
        integration,
        backlog,
    }
}
