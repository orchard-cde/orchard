package dev.orchard.core.model;

import java.util.List;
import java.util.Map;

/**
 * A Seed for a devcontainer.json-based workspace.
 *
 * @see <a href="https://containers.dev/implementors/json_reference/">devcontainer spec</a>
 *
 * @param features Devcontainer features keyed by feature ID; each value is the
 *                 feature's options map (empty when no options are configured,
 *                 never null). Insertion order from devcontainer.json is preserved.
 */
public final class DevcontainerSeed extends Seed {

    private final String dockerfilePath;
    private final String dockerComposeFile;
    private final String service;
    private final Map<String, String> buildArgs;
    private final Map<String, Map<String, Object>> features;
    private final LifecycleCommand initializeCommand;
    private final LifecycleCommand onCreateCommand;
    private final LifecycleCommand updateContentCommand;
    private final LifecycleCommand postCreateCommand;
    private final LifecycleCommand postStartCommand;
    private final LifecycleCommand postAttachCommand;
    private final WaitFor waitFor;
    private final VsCodeCustomizations vscodeCustomizations;

    private DevcontainerSeed(
            String name,
            String image,
            List<String> forwardPorts,
            Map<String, String> containerEnv,
            String dockerfilePath,
            String dockerComposeFile,
            String service,
            Map<String, String> buildArgs,
            Map<String, Map<String, Object>> features,
            LifecycleCommand initializeCommand,
            LifecycleCommand onCreateCommand,
            LifecycleCommand updateContentCommand,
            LifecycleCommand postCreateCommand,
            LifecycleCommand postStartCommand,
            LifecycleCommand postAttachCommand,
            WaitFor waitFor,
            VsCodeCustomizations vscodeCustomizations) {
        super(name, image, forwardPorts, containerEnv);
        this.dockerfilePath = dockerfilePath;
        this.dockerComposeFile = dockerComposeFile;
        this.service = service;
        this.buildArgs = buildArgs != null ? buildArgs : Map.of();
        this.features = features != null ? features : Map.of();
        this.initializeCommand = initializeCommand;
        this.onCreateCommand = onCreateCommand;
        this.updateContentCommand = updateContentCommand;
        this.postCreateCommand = postCreateCommand;
        this.postStartCommand = postStartCommand;
        this.postAttachCommand = postAttachCommand;
        this.waitFor = waitFor;
        this.vscodeCustomizations = vscodeCustomizations;
    }

    public String dockerfilePath() { return dockerfilePath; }
    public String dockerComposeFile() { return dockerComposeFile; }
    public String service() { return service; }
    public Map<String, String> buildArgs() { return buildArgs; }
    public Map<String, Map<String, Object>> features() { return features; }
    public LifecycleCommand initializeCommand() { return initializeCommand; }
    public LifecycleCommand onCreateCommand() { return onCreateCommand; }
    public LifecycleCommand updateContentCommand() { return updateContentCommand; }
    public LifecycleCommand postCreateCommand() { return postCreateCommand; }
    public LifecycleCommand postStartCommand() { return postStartCommand; }
    public LifecycleCommand postAttachCommand() { return postAttachCommand; }
    public WaitFor waitFor() { return waitFor; }
    public VsCodeCustomizations vscodeCustomizations() { return vscodeCustomizations; }

    public WaitFor effectiveWaitFor() {
        return waitFor != null ? waitFor : WaitFor.UPDATE_CONTENT_COMMAND;
    }

    public record VsCodeCustomizations(
        List<String> extensions,
        Map<String, Object> settings
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String image;
        private List<String> forwardPorts = List.of();
        private Map<String, String> containerEnv = Map.of();
        private String dockerfilePath;
        private String dockerComposeFile;
        private String service;
        private Map<String, String> buildArgs = Map.of();
        private Map<String, Map<String, Object>> features = Map.of();
        private LifecycleCommand initializeCommand;
        private LifecycleCommand onCreateCommand;
        private LifecycleCommand updateContentCommand;
        private LifecycleCommand postCreateCommand;
        private LifecycleCommand postStartCommand;
        private LifecycleCommand postAttachCommand;
        private WaitFor waitFor;
        private VsCodeCustomizations vscodeCustomizations;

        public Builder name(String name) { this.name = name; return this; }
        public Builder image(String image) { this.image = image; return this; }
        public Builder forwardPorts(List<String> forwardPorts) { this.forwardPorts = forwardPorts; return this; }
        public Builder containerEnv(Map<String, String> containerEnv) { this.containerEnv = containerEnv; return this; }
        public Builder dockerfilePath(String dockerfilePath) { this.dockerfilePath = dockerfilePath; return this; }
        public Builder dockerComposeFile(String dockerComposeFile) { this.dockerComposeFile = dockerComposeFile; return this; }
        public Builder service(String service) { this.service = service; return this; }
        public Builder buildArgs(Map<String, String> buildArgs) { this.buildArgs = buildArgs; return this; }
        public Builder features(Map<String, Map<String, Object>> features) { this.features = features; return this; }
        public Builder initializeCommand(LifecycleCommand v) { this.initializeCommand = v; return this; }
        public Builder onCreateCommand(LifecycleCommand v) { this.onCreateCommand = v; return this; }
        public Builder updateContentCommand(LifecycleCommand v) { this.updateContentCommand = v; return this; }
        public Builder postCreateCommand(LifecycleCommand v) { this.postCreateCommand = v; return this; }
        public Builder postStartCommand(LifecycleCommand v) { this.postStartCommand = v; return this; }
        public Builder postAttachCommand(LifecycleCommand v) { this.postAttachCommand = v; return this; }
        public Builder waitFor(WaitFor waitFor) { this.waitFor = waitFor; return this; }
        public Builder vscodeCustomizations(VsCodeCustomizations v) { this.vscodeCustomizations = v; return this; }

        public DevcontainerSeed build() {
            return new DevcontainerSeed(name, image, forwardPorts, containerEnv,
                dockerfilePath, dockerComposeFile, service, buildArgs, features,
                initializeCommand, onCreateCommand, updateContentCommand,
                postCreateCommand, postStartCommand, postAttachCommand,
                waitFor, vscodeCustomizations);
        }
    }
}
