package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class DevcontainerSeed extends Seed {

    // --- build.* fields (issue #31) ----------------------------------------------------------

    private final String dockerfilePath;
    private final String buildContext;
    private final String buildTarget;
    private final List<String> buildCacheFrom;
    private final List<String> buildOptions;
    private final Map<String, String> buildArgs;

    // --- compose fields (issue #32) ----------------------------------------------------------

    /** All compose files in order (spec allows string or array). */
    private final List<String> dockerComposeFiles;
    private final String service;
    private final List<String> runServices;

    // --- features (existing) -----------------------------------------------------------------

    private final Map<String, Map<String, Object>> features;
    private final List<String> overrideFeatureInstallOrder;  // issue #105

    // --- runtime / container options (issue #29) ---------------------------------------------

    private final String remoteUser;
    private final String containerUser;
    private final Map<String, String> remoteEnv;
    private final List<String> mounts;
    private final List<String> runArgs;
    private final String workspaceFolder;
    private final String workspaceMount;
    private final Boolean privileged;
    private final Boolean init;
    private final Boolean overrideCommand;
    private final Boolean updateRemoteUserUID;
    private final List<String> capAdd;
    private final List<String> securityOpt;
    private final String shutdownAction;

    // --- port attributes (issue #101) --------------------------------------------------------

    private final Map<String, PortAttributes> portsAttributes;
    private final PortAttributes otherPortsAttributes;

    // --- user env probe (issue #102) ---------------------------------------------------------

    private final UserEnvProbe userEnvProbe;

    // --- host requirements (issue #103) ------------------------------------------------------

    private final HostRequirements hostRequirements;

    // --- secrets (issue #104) ----------------------------------------------------------------

    private final Map<String, SecretDeclaration> secrets;

    // --- lifecycle commands (existing) -------------------------------------------------------

    private final LifecycleCommand initializeCommand;
    private final LifecycleCommand onCreateCommand;
    private final LifecycleCommand updateContentCommand;
    private final LifecycleCommand postCreateCommand;
    private final LifecycleCommand postStartCommand;
    private final LifecycleCommand postAttachCommand;
    private final WaitFor waitFor;

    // --- VS Code customizations (existing) ---------------------------------------------------

    private final VsCodeCustomizations vscodeCustomizations;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    private DevcontainerSeed(
            @JsonProperty("name") String name,
            @JsonProperty("image") String image,
            @JsonProperty("forwardPorts") List<String> forwardPorts,
            @JsonProperty("containerEnv") Map<String, String> containerEnv,
            @JsonProperty("dockerfilePath") String dockerfilePath,
            @JsonProperty("buildContext") String buildContext,
            @JsonProperty("buildTarget") String buildTarget,
            @JsonProperty("buildCacheFrom") List<String> buildCacheFrom,
            @JsonProperty("buildOptions") List<String> buildOptions,
            @JsonProperty("buildArgs") Map<String, String> buildArgs,
            @JsonProperty("dockerComposeFiles") List<String> dockerComposeFiles,
            @JsonProperty("service") String service,
            @JsonProperty("runServices") List<String> runServices,
            @JsonProperty("features") Map<String, Map<String, Object>> features,
            @JsonProperty("overrideFeatureInstallOrder") List<String> overrideFeatureInstallOrder,
            @JsonProperty("remoteUser") String remoteUser,
            @JsonProperty("containerUser") String containerUser,
            @JsonProperty("remoteEnv") Map<String, String> remoteEnv,
            @JsonProperty("mounts") List<String> mounts,
            @JsonProperty("runArgs") List<String> runArgs,
            @JsonProperty("workspaceFolder") String workspaceFolder,
            @JsonProperty("workspaceMount") String workspaceMount,
            @JsonProperty("privileged") Boolean privileged,
            @JsonProperty("init") Boolean init,
            @JsonProperty("overrideCommand") Boolean overrideCommand,
            @JsonProperty("updateRemoteUserUID") Boolean updateRemoteUserUID,
            @JsonProperty("capAdd") List<String> capAdd,
            @JsonProperty("securityOpt") List<String> securityOpt,
            @JsonProperty("shutdownAction") String shutdownAction,
            @JsonProperty("portsAttributes") Map<String, PortAttributes> portsAttributes,
            @JsonProperty("otherPortsAttributes") PortAttributes otherPortsAttributes,
            @JsonProperty("userEnvProbe") UserEnvProbe userEnvProbe,
            @JsonProperty("hostRequirements") HostRequirements hostRequirements,
            @JsonProperty("secrets") Map<String, SecretDeclaration> secrets,
            @JsonProperty("initializeCommand") LifecycleCommand initializeCommand,
            @JsonProperty("onCreateCommand") LifecycleCommand onCreateCommand,
            @JsonProperty("updateContentCommand") LifecycleCommand updateContentCommand,
            @JsonProperty("postCreateCommand") LifecycleCommand postCreateCommand,
            @JsonProperty("postStartCommand") LifecycleCommand postStartCommand,
            @JsonProperty("postAttachCommand") LifecycleCommand postAttachCommand,
            @JsonProperty("waitFor") WaitFor waitFor,
            @JsonProperty("vscodeCustomizations") VsCodeCustomizations vscodeCustomizations) {
        super(name, image, forwardPorts, containerEnv);
        this.dockerfilePath = dockerfilePath;
        this.buildContext = buildContext;
        this.buildTarget = buildTarget;
        this.buildCacheFrom = buildCacheFrom != null ? buildCacheFrom : List.of();
        this.buildOptions = buildOptions != null ? buildOptions : List.of();
        this.buildArgs = buildArgs != null ? buildArgs : Map.of();
        this.dockerComposeFiles = dockerComposeFiles != null ? dockerComposeFiles : List.of();
        this.service = service;
        this.runServices = runServices != null ? runServices : List.of();
        this.features = features != null ? features : Map.of();
        this.overrideFeatureInstallOrder = overrideFeatureInstallOrder != null ? overrideFeatureInstallOrder : List.of();
        this.remoteUser = remoteUser;
        this.containerUser = containerUser;
        this.remoteEnv = remoteEnv != null ? remoteEnv : Map.of();
        this.mounts = mounts != null ? mounts : List.of();
        this.runArgs = runArgs != null ? runArgs : List.of();
        this.workspaceFolder = workspaceFolder;
        this.workspaceMount = workspaceMount;
        this.privileged = privileged;
        this.init = init;
        this.overrideCommand = overrideCommand;
        this.updateRemoteUserUID = updateRemoteUserUID;
        this.capAdd = capAdd != null ? capAdd : List.of();
        this.securityOpt = securityOpt != null ? securityOpt : List.of();
        this.shutdownAction = shutdownAction;
        this.portsAttributes = portsAttributes != null ? portsAttributes : Map.of();
        this.otherPortsAttributes = otherPortsAttributes;
        this.userEnvProbe = userEnvProbe;
        this.hostRequirements = hostRequirements;
        this.secrets = secrets != null ? secrets : Map.of();
        this.initializeCommand = initializeCommand;
        this.onCreateCommand = onCreateCommand;
        this.updateContentCommand = updateContentCommand;
        this.postCreateCommand = postCreateCommand;
        this.postStartCommand = postStartCommand;
        this.postAttachCommand = postAttachCommand;
        this.waitFor = waitFor;
        this.vscodeCustomizations = vscodeCustomizations;
    }

    // --- accessors ---------------------------------------------------------------------------

    public String dockerfilePath() { return dockerfilePath; }
    public String buildContext() { return buildContext; }
    public String buildTarget() { return buildTarget; }
    public List<String> buildCacheFrom() { return buildCacheFrom; }
    public List<String> buildOptions() { return buildOptions; }
    public Map<String, String> buildArgs() { return buildArgs; }

    /** All compose files (first entry is the primary file). */
    public List<String> dockerComposeFiles() { return dockerComposeFiles; }
    /**
     * Convenience — returns the first compose file, or {@code null} if none.
     * Callers that only need to know whether this is a compose seed can check
     * {@code !dockerComposeFiles().isEmpty()}.
     */
    public String dockerComposeFile() {
        return dockerComposeFiles.isEmpty() ? null : dockerComposeFiles.get(0);
    }

    public String service() { return service; }
    public List<String> runServices() { return runServices; }
    public Map<String, Map<String, Object>> features() { return features; }
    public List<String> overrideFeatureInstallOrder() { return overrideFeatureInstallOrder; }
    public String remoteUser() { return remoteUser; }
    public String containerUser() { return containerUser; }
    public Map<String, String> remoteEnv() { return remoteEnv; }
    public List<String> mounts() { return mounts; }
    public List<String> runArgs() { return runArgs; }
    public String workspaceFolder() { return workspaceFolder; }
    public String workspaceMount() { return workspaceMount; }
    public Boolean privileged() { return privileged; }
    public Boolean init() { return init; }
    public Boolean overrideCommand() { return overrideCommand; }
    public Boolean updateRemoteUserUID() { return updateRemoteUserUID; }
    public List<String> capAdd() { return capAdd; }
    public List<String> securityOpt() { return securityOpt; }
    public String shutdownAction() { return shutdownAction; }
    public Map<String, PortAttributes> portsAttributes() { return portsAttributes; }
    public PortAttributes otherPortsAttributes() { return otherPortsAttributes; }
    public UserEnvProbe userEnvProbe() { return userEnvProbe; }
    public HostRequirements hostRequirements() { return hostRequirements; }
    public Map<String, SecretDeclaration> secrets() { return secrets; }
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

    // --- nested types ------------------------------------------------------------------------

    public record VsCodeCustomizations(
        List<String> extensions,
        Map<String, Object> settings
    ) {}

    /**
     * Per-port forwarding attributes ({@code portsAttributes} / {@code otherPortsAttributes}).
     * Issue #101.
     */
    public record PortAttributes(
        String label,
        String protocol,
        String onAutoForward,
        Boolean requireLocalPort,
        Boolean elevateIfNeeded
    ) {}

    /**
     * Enum for the {@code userEnvProbe} field. Issue #102.
     * Controls which shell startup files are sourced when probing the remote user environment.
     */
    public enum UserEnvProbe {
        NONE,
        LOGIN_SHELL,
        LOGIN_INTERACTIVE_SHELL,
        INTERACTIVE_SHELL;

        /** Case-insensitive parse matching the camelCase spec values. */
        public static UserEnvProbe fromSpec(String value) {
            if (value == null) return null;
            return switch (value.toLowerCase()) {
                case "none"                   -> NONE;
                case "loginshell"             -> LOGIN_SHELL;
                case "logininteractiveshell"  -> LOGIN_INTERACTIVE_SHELL;
                case "interactiveshell"       -> INTERACTIVE_SHELL;
                default                       -> null;
            };
        }
    }

    /**
     * Host hardware requirements declared in {@code hostRequirements}. Issue #103.
     * {@code gpu} is stored as a raw {@link Object} because the spec allows
     * {@code boolean}, the string {@code "optional"}, or an object with
     * {@code cores}/{@code memory} sub-fields.
     */
    public record HostRequirements(
        Integer cpus,
        String memory,
        String storage,
        Object gpu
    ) {}

    /**
     * A single entry in the {@code secrets} map. Issue #104.
     * Declares what secret a project needs; never holds the value.
     */
    public record SecretDeclaration(
        String description,
        String documentationUrl
    ) {}

    // --- builder -----------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String image;
        private List<String> forwardPorts = List.of();
        private Map<String, String> containerEnv = Map.of();
        private String dockerfilePath;
        private String buildContext;
        private String buildTarget;
        private List<String> buildCacheFrom = List.of();
        private List<String> buildOptions = List.of();
        private Map<String, String> buildArgs = Map.of();
        private List<String> dockerComposeFiles = List.of();
        private String service;
        private List<String> runServices = List.of();
        private Map<String, Map<String, Object>> features = Map.of();
        private List<String> overrideFeatureInstallOrder = List.of();
        private String remoteUser;
        private String containerUser;
        private Map<String, String> remoteEnv = Map.of();
        private List<String> mounts = List.of();
        private List<String> runArgs = List.of();
        private String workspaceFolder;
        private String workspaceMount;
        private Boolean privileged;
        private Boolean init;
        private Boolean overrideCommand;
        private Boolean updateRemoteUserUID;
        private List<String> capAdd = List.of();
        private List<String> securityOpt = List.of();
        private String shutdownAction;
        private Map<String, PortAttributes> portsAttributes = Map.of();
        private PortAttributes otherPortsAttributes;
        private UserEnvProbe userEnvProbe;
        private HostRequirements hostRequirements;
        private Map<String, SecretDeclaration> secrets = Map.of();
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
        public Builder buildContext(String buildContext) { this.buildContext = buildContext; return this; }
        public Builder buildTarget(String buildTarget) { this.buildTarget = buildTarget; return this; }
        public Builder buildCacheFrom(List<String> buildCacheFrom) { this.buildCacheFrom = buildCacheFrom; return this; }
        public Builder buildOptions(List<String> buildOptions) { this.buildOptions = buildOptions; return this; }
        public Builder buildArgs(Map<String, String> buildArgs) { this.buildArgs = buildArgs; return this; }
        public Builder dockerComposeFiles(List<String> dockerComposeFiles) { this.dockerComposeFiles = dockerComposeFiles; return this; }
        public Builder service(String service) { this.service = service; return this; }
        public Builder runServices(List<String> runServices) { this.runServices = runServices; return this; }
        public Builder features(Map<String, Map<String, Object>> features) { this.features = features; return this; }
        public Builder overrideFeatureInstallOrder(List<String> v) { this.overrideFeatureInstallOrder = v; return this; }
        public Builder remoteUser(String remoteUser) { this.remoteUser = remoteUser; return this; }
        public Builder containerUser(String containerUser) { this.containerUser = containerUser; return this; }
        public Builder remoteEnv(Map<String, String> remoteEnv) { this.remoteEnv = remoteEnv; return this; }
        public Builder mounts(List<String> mounts) { this.mounts = mounts; return this; }
        public Builder runArgs(List<String> runArgs) { this.runArgs = runArgs; return this; }
        public Builder workspaceFolder(String workspaceFolder) { this.workspaceFolder = workspaceFolder; return this; }
        public Builder workspaceMount(String workspaceMount) { this.workspaceMount = workspaceMount; return this; }
        public Builder privileged(Boolean privileged) { this.privileged = privileged; return this; }
        public Builder init(Boolean init) { this.init = init; return this; }
        public Builder overrideCommand(Boolean overrideCommand) { this.overrideCommand = overrideCommand; return this; }
        public Builder updateRemoteUserUID(Boolean updateRemoteUserUID) { this.updateRemoteUserUID = updateRemoteUserUID; return this; }
        public Builder capAdd(List<String> capAdd) { this.capAdd = capAdd; return this; }
        public Builder securityOpt(List<String> securityOpt) { this.securityOpt = securityOpt; return this; }
        public Builder shutdownAction(String shutdownAction) { this.shutdownAction = shutdownAction; return this; }
        public Builder portsAttributes(Map<String, PortAttributes> portsAttributes) { this.portsAttributes = portsAttributes; return this; }
        public Builder otherPortsAttributes(PortAttributes otherPortsAttributes) { this.otherPortsAttributes = otherPortsAttributes; return this; }
        public Builder userEnvProbe(UserEnvProbe userEnvProbe) { this.userEnvProbe = userEnvProbe; return this; }
        public Builder hostRequirements(HostRequirements hostRequirements) { this.hostRequirements = hostRequirements; return this; }
        public Builder secrets(Map<String, SecretDeclaration> secrets) { this.secrets = secrets; return this; }
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
                dockerfilePath, buildContext, buildTarget, buildCacheFrom, buildOptions, buildArgs,
                dockerComposeFiles, service, runServices,
                features, overrideFeatureInstallOrder,
                remoteUser, containerUser, remoteEnv, mounts, runArgs,
                workspaceFolder, workspaceMount, privileged, init, overrideCommand, updateRemoteUserUID,
                capAdd, securityOpt, shutdownAction,
                portsAttributes, otherPortsAttributes, userEnvProbe, hostRequirements, secrets,
                initializeCommand, onCreateCommand, updateContentCommand,
                postCreateCommand, postStartCommand, postAttachCommand,
                waitFor, vscodeCustomizations);
        }
    }
}
