package dev.orchard.nursery;

/**
 * Successful result of {@code devcontainer up} (outcome=success line in the CLI JSON stream).
 * {@code composeProjectName} is non-null only when CLI is run against a compose-based devcontainer.
 *
 * <p>Wire shape (from {@code @devcontainers/cli@0.87.0} source):
 * <pre>
 *   {outcome: "success", containerId, composeProjectName?, remoteUser, remoteWorkspaceFolder}
 * </pre>
 */
public record DevcontainerCliResult(
    String containerId,
    String composeProjectName,
    String remoteUser,
    String remoteWorkspaceFolder
) {}
