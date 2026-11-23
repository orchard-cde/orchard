package dev.orchard.core.model;

import java.util.List;
import java.util.Map;

/**
 * The wire shape of {@code devcontainer.json}.
 * Spec: https://containers.dev/implementors/json_reference/
 *
 * <p>When {@code dockerFile} is set and {@code build} is absent, a reader binds the root object
 * itself as {@link Build}: the legacy spelling keeps {@code context}, {@code args}, ... at the root.
 */
public record DevcontainerJson(
    String name,
    String image,
    String dockerFile,
    Build build,
    List<String> dockerComposeFile,
    String service,
    List<String> runServices,
    Map<String, Map<String, Object>> features,
    List<String> overrideFeatureInstallOrder,
    List<String> forwardPorts,
    Map<String, String> containerEnv,
    Map<String, String> remoteEnv,
    String remoteUser,
    String containerUser,
    List<String> mounts,
    List<String> runArgs,
    String workspaceFolder,
    String workspaceMount,
    Boolean privileged,
    Boolean init,
    Boolean overrideCommand,
    Boolean updateRemoteUserUID,
    List<String> capAdd,
    List<String> securityOpt,
    String shutdownAction,
    Map<String, DevcontainerSeed.PortAttributes> portsAttributes,
    DevcontainerSeed.PortAttributes otherPortsAttributes,
    String userEnvProbe,
    HostRequirements hostRequirements,
    Map<String, DevcontainerSeed.SecretDeclaration> secrets,
    LifecycleCommand initializeCommand,
    LifecycleCommand onCreateCommand,
    LifecycleCommand updateContentCommand,
    LifecycleCommand postCreateCommand,
    LifecycleCommand postStartCommand,
    LifecycleCommand postAttachCommand,
    String waitFor,
    Customizations customizations
) {

    public record Build(
        String dockerfile,
        String dockerFile,
        String context,
        String target,
        List<String> cacheFrom,
        List<String> options,
        Map<String, String> args
    ) {}

    /** {@code gpu} is {@code true}/{@code false}, {@code "optional"}, or {@code {cores, memory}}. */
    public record HostRequirements(Integer cpus, String memory, String storage, Object gpu) {}

    public record Customizations(VsCode vscode) {}

    public record VsCode(List<String> extensions, Map<String, Object> settings) {}
}
