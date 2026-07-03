package org.apache.camel.karavan.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Docker Stack file (e.g., docker-stack.yml) for Swarm mode.
 * * Assumes you have corresponding classes:
 * - DockerStackService (from the previous response)
 * - DockerNetworkDefinition (represents network definitions)
 * - DockerVolumeDefinition (represents top-level volume definitions)
 */
@Getter
@Setter
@NoArgsConstructor
public class DockerStack {
    private String version;
    private Map<String, DockerStackService> services = new HashMap<>();
    private Map<String, DockerNetworkDefinition> networks = new HashMap<>();
    private Map<String, DockerVolumeDefinition> volumes = new HashMap<>(); // Added for stack files

    public DockerStack(Map<String, DockerStackService> services) {
        this.services = services;
    }

    // Note: The static 'create' method was removed.
    // It relied on 'container_name', which is not supported in Docker Swarm.
    // You would now add a service like this:
    //   myStack.getServices().put("my-service-name", myStackServiceInstance);
}
