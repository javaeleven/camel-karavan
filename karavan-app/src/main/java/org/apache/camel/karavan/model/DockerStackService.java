package org.apache.camel.karavan.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a service definition for a Docker Stack (Swarm mode).
 * * Note: Assumes you also have/create DockerVolumeDefinition and DockerHealthCheckDefinition
 * classes, which would be the stack-compatible versions of your
 * DockerComposeVolume and DockerComposeHealthCheck classes.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(of = {"image", "ports", "networks", "expose", "depends_on", "environment", "healthcheck",
        "volumes", "labels", "deploy"})
public class DockerStackService {

    // Fields that are the same as DockerComposeService
    private String image;
    private String hostname;
    private String command;
    private List<String> ports = new ArrayList<>();
    private List<DockerConfigDefinition> configs = new ArrayList<>();
    private List<DockerVolumeDefinition> volumes = new ArrayList<>();
    private List<String> expose = new ArrayList<>();
    private List<String> depends_on = new ArrayList<>();
    private List<String> networks = new ArrayList<>();
    private Map<String, String> environment = new HashMap<>();
    private DockerHealthCheckDefinition healthcheck; // Renamed class
    private Map<String, String> labels = new HashMap<>();

    // --- Fields moved from top-level to the 'deploy' block ---
    // container_name: REMOVED (not supported in stack)
    // restart: MOVED to deploy.restart_policy
    // cpus, cpu_percent, mem_limit, mem_reservation: MOVED to deploy.resources

    /**
     * NEW: The 'deploy' block for Swarm-specific configuration
     */
    private Deploy deploy;

    public Map<Integer, Integer> getPortsMap() {
        Map<Integer, Integer> p = new HashMap<>();
        if (ports != null && !ports.isEmpty()) {
            ports.forEach(s -> {
                String[] values = s.split(":");
                p.put(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
            });
        }
        return p;
    }

    public Map<String, String> getEnvironment() {
        return environment != null ? environment : new HashMap<>();
    }

    public List<String> getEnvironmentList() {
        return environment != null
                ? environment.entrySet().stream().map(e -> e.getKey().concat("=").concat(e.getValue())).collect(Collectors.toList())
                : new ArrayList<>();
    }

    public void addEnvironment(String key, String value) {
        Map<String, String> map = getEnvironment();
        map.put(key, value);
        setEnvironment(map);
    }

    public void addConfig(DockerConfigDefinition config) {
        this.configs.add(config);
    }

    public void addLabel(String key, String value) {
        this.labels.put(key, value);
    }

    // --- NESTED CLASSES FOR THE 'deploy' BLOCK ---

    /**
     * Corresponds to the 'deploy' section of a stack file.
     */
    @Getter
    @Setter
    public static class Deploy {
        private Integer replicas;
        private RestartPolicy restart_policy;
        private Resources resources;
        private Map<String, String> labels; // Note: these are *container* labels, not *service* labels
    }

    /**
     * Corresponds to 'deploy.restart_policy'
     */
    @Getter
    @Setter
    public static class RestartPolicy {
        private String condition; // e.g., "on-failure", "any"
        private String delay; // e.g., "5s"
        private Integer max_attempts;
        private String window; // e.g., "120s"
    }

    /**
     * Corresponds to 'deploy.resources'
     */
    @Getter
    @Setter
    public static class Resources {
        private ResourceLimit limits;
        private ResourceLimit reservations;
    }

    /**
     * Corresponds to 'deploy.resources.limits' and 'deploy.resources.reservations'
     */
    @Getter
    @Setter
    public static class ResourceLimit {
        private String cpus; // e.g., "0.50"
        private String memory; // e.g., "512M"
    }

    // You would also need to define these classes,
    // presumably by renaming/adapting them from your DockerCompose versions.
    // public static class DockerVolumeDefinition { /* ... */ }
    // public static class DockerHealthCheckDefinition { /* ... */ }
}
