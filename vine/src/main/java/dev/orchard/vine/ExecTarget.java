package dev.orchard.vine;

import dev.orchard.core.model.ContainerRuntime;

import java.util.UUID;

/**
 * Where to run something, and what to call it in logs. Substrate-neutral: a VM-backed provider
 * builds one from a seedling's endpoint, a container-backed one from a container handle, and
 * nothing downstream of here can tell the difference.
 *
 * @param runner        the command channel, running where the container and devcontainer CLIs live
 * @param workspacePath the absolute path the repository is checked out at on that channel
 * @param targetId      the substrate's id — correlation only, never used to address anything
 * @param runtime       the container runtime whose CLI the channel invokes
 */
public record ExecTarget(CommandRunner runner, String workspacePath, UUID targetId, ContainerRuntime runtime) {}
