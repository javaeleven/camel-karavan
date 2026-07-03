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
package org.apache.camel.karavan.api;

import io.quarkus.security.Authenticated;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.cache.KaravanCache;
import org.apache.camel.karavan.model.CamelStatus;
import org.apache.camel.karavan.model.CamelStatusValue;

import java.util.List;

import static org.apache.camel.karavan.KaravanEvents.CMD_CLEAR_ALL_STATUSES;

@Path("/ui/status")
public class StatusResource {

    @Inject
    KaravanCache karavanCache;

    @Inject
    EventBus eventBus;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/camel")
    @Authenticated
    public List<CamelStatus> getCamelAllStatuses() {
        return karavanCache.getCamelAllStatuses();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/camel/{statusName}")
    @Authenticated
    public List<CamelStatus> getCamelContextStatusesByName(@PathParam("statusName") String statusName) {
        return karavanCache.getCamelStatusesByName(CamelStatusValue.Name.valueOf(statusName));
    }

    @DELETE
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/all")
    public Response deleteAllStatuses() {
        karavanCache.clearAllStatuses();
        eventBus.publish(CMD_CLEAR_ALL_STATUSES, "");
        return Response.ok().build();
    }
}