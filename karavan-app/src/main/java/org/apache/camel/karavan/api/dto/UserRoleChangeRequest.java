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
package org.apache.camel.karavan.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Typed, validated payload for PUT /ui/access/userRole (was an untyped JsonObject).
 */
@Getter
@Setter
@NoArgsConstructor
public class UserRoleChangeRequest {
    @NotBlank(message = "username is required")
    private String username;
    @NotBlank(message = "role is required")
    private String role;
    @NotBlank(message = "command is required")
    @Pattern(regexp = "add|remove", message = "command must be 'add' or 'remove'")
    private String command;
}
