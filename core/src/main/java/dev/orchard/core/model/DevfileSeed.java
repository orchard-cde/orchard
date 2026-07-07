package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A {@link Seed} for a {@code devfile.yaml}-based workspace.
 *
 * <p>Carries fields from the devfile {@code container} component that drive the
 * VM + SSH provisioning path. Format-specific fields beyond the common {@link Seed}
 * base (image, forwardPorts, containerEnv, name) live here.
 *
 * <p>{@code events.preStart} and {@code events.postStart} (resolved against {@code exec}
 * commands in the top-level {@code commands} array) map to {@link #preStartCommand()} and
 * {@link #postStartCommand()}. Multiple command IDs bound to one event are composed into a
 * single shell line joined with {@code &&}.
 *
 * <p>Out of scope for the current slice: {@code kubernetes} / {@code openshift}
 * components, {@code apply}/{@code composite} commands, and the {@code preStop}/{@code postStop}
 * events. Those are tracked in separate issues.
 */
public final class DevfileSeed extends Seed {

    /** The devfile component name (distinct from the workspace name in {@link #name()}). */
    private final String componentName;

    /** Kubernetes-style memory limit, e.g. {@code "512Mi"}, {@code "1Gi"}. */
    private final String memoryLimit;

    /** Kubernetes-style memory request, e.g. {@code "256Mi"}. */
    private final String memoryRequest;

    /** Kubernetes-style CPU limit, e.g. {@code "500m"}, {@code "1"}. */
    private final String cpuLimit;

    /** Kubernetes-style CPU request, e.g. {@code "100m"}. */
    private final String cpuRequest;

    /** Runs on the seedling host, before the container starts (devfile {@code events.preStart}). */
    private final LifecycleCommand preStartCommand;

    /** Runs inside the container, after it starts (devfile {@code events.postStart}). */
    private final LifecycleCommand postStartCommand;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    private DevfileSeed(
            @JsonProperty("name") String name,
            @JsonProperty("image") String image,
            @JsonProperty("forwardPorts") List<String> forwardPorts,
            @JsonProperty("containerEnv") Map<String, String> containerEnv,
            @JsonProperty("componentName") String componentName,
            @JsonProperty("memoryLimit") String memoryLimit,
            @JsonProperty("memoryRequest") String memoryRequest,
            @JsonProperty("cpuLimit") String cpuLimit,
            @JsonProperty("cpuRequest") String cpuRequest,
            @JsonProperty("preStartCommand") LifecycleCommand preStartCommand,
            @JsonProperty("postStartCommand") LifecycleCommand postStartCommand) {
        super(name, image, forwardPorts, containerEnv);
        this.componentName = componentName;
        this.memoryLimit = memoryLimit;
        this.memoryRequest = memoryRequest;
        this.cpuLimit = cpuLimit;
        this.cpuRequest = cpuRequest;
        this.preStartCommand = preStartCommand;
        this.postStartCommand = postStartCommand;
    }

    public String componentName() { return componentName; }
    public String memoryLimit() { return memoryLimit; }
    public String memoryRequest() { return memoryRequest; }
    public String cpuLimit() { return cpuLimit; }
    public String cpuRequest() { return cpuRequest; }
    public LifecycleCommand preStartCommand() { return preStartCommand; }
    public LifecycleCommand postStartCommand() { return postStartCommand; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String image;
        private List<String> forwardPorts = List.of();
        private Map<String, String> containerEnv = Map.of();
        private String componentName;
        private String memoryLimit;
        private String memoryRequest;
        private String cpuLimit;
        private String cpuRequest;
        private LifecycleCommand preStartCommand;
        private LifecycleCommand postStartCommand;

        public Builder name(String name) { this.name = name; return this; }
        public Builder image(String image) { this.image = image; return this; }
        public Builder forwardPorts(List<String> forwardPorts) { this.forwardPorts = forwardPorts; return this; }
        public Builder containerEnv(Map<String, String> containerEnv) { this.containerEnv = containerEnv; return this; }
        public Builder componentName(String componentName) { this.componentName = componentName; return this; }
        public Builder memoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; return this; }
        public Builder memoryRequest(String memoryRequest) { this.memoryRequest = memoryRequest; return this; }
        public Builder cpuLimit(String cpuLimit) { this.cpuLimit = cpuLimit; return this; }
        public Builder cpuRequest(String cpuRequest) { this.cpuRequest = cpuRequest; return this; }
        public Builder preStartCommand(LifecycleCommand preStartCommand) { this.preStartCommand = preStartCommand; return this; }
        public Builder postStartCommand(LifecycleCommand postStartCommand) { this.postStartCommand = postStartCommand; return this; }

        public DevfileSeed build() {
            return new DevfileSeed(name, image, forwardPorts, containerEnv,
                componentName, memoryLimit, memoryRequest, cpuLimit, cpuRequest,
                preStartCommand, postStartCommand);
        }
    }
}
