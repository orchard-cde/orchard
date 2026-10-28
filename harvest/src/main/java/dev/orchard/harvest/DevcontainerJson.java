package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;

/**
 * The wire shape of {@code devcontainer.json}, as bound by {@link DevcontainerParser}.
 * Spec: https://containers.dev/implementors/json_reference/
 *
 * <p>When {@code dockerFile} is set and {@code build} is absent, the parser binds the root object
 * itself as {@link Build}: the legacy spelling keeps {@code context}, {@code args}, ... at the root.
 */
public record DevcontainerJson(
    String name,
    String image,
    String dockerFile,
    Build build,
    @JsonDeserialize(using = LenientBinding.StringOrList.class) List<String> dockerComposeFile,
    String service,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> runServices,
    @JsonDeserialize(contentUsing = LenientBinding.ObjectOnly.class) Map<String, Map<String, Object>> features,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> overrideFeatureInstallOrder,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> forwardPorts,
    Map<String, String> containerEnv,
    Map<String, String> remoteEnv,
    String remoteUser,
    String containerUser,
    @JsonDeserialize(using = LenientBinding.Mounts.class) List<String> mounts,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> runArgs,
    String workspaceFolder,
    String workspaceMount,
    Boolean privileged,
    Boolean init,
    Boolean overrideCommand,
    Boolean updateRemoteUserUID,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> capAdd,
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> securityOpt,
    String shutdownAction,
    Map<String, DevcontainerSeed.PortAttributes> portsAttributes,
    @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) DevcontainerSeed.PortAttributes otherPortsAttributes,
    String userEnvProbe,
    @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) HostRequirements hostRequirements,
    Map<String, DevcontainerSeed.SecretDeclaration> secrets,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand initializeCommand,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand onCreateCommand,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand updateContentCommand,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postCreateCommand,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postStartCommand,
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postAttachCommand,
    String waitFor,
    Customizations customizations
) {

    public record Build(
        String dockerfile,
        String dockerFile,
        String context,
        String target,
        @JsonDeserialize(using = LenientBinding.StringOrList.class) List<String> cacheFrom,
        @JsonDeserialize(using = LenientBinding.StringList.class) List<String> options,
        Map<String, String> args
    ) {}

    /** {@code gpu} is {@code true}/{@code false}, {@code "optional"}, or {@code {cores, memory}}. */
    public record HostRequirements(Integer cpus, String memory, String storage, Object gpu) {}

    public record Customizations(@JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) VsCode vscode) {}

    public record VsCode(
        @JsonDeserialize(using = LenientBinding.StringList.class) List<String> extensions,
        @JsonDeserialize(using = LenientBinding.Settings.class) Map<String, Object> settings) {}
}
