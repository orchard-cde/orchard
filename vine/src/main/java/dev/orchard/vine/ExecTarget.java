package dev.orchard.vine;

import dev.orchard.core.model.ContainerRuntime;

import java.util.UUID;

/**
 * Where to run something, and which plot it belongs to. Substrate-neutral: a VM-backed provider
 * builds one from a seedling's endpoint, a container-backed one from a container handle, and
 * nothing downstream of here can tell the difference.
 *
 * @param runner        the command channel, running where the container and devcontainer CLIs live
 * @param workspacePath the absolute path the repository is checked out at on that channel
 * @param targetId      the plot's id; every fruit container grown here is labelled with it
 *                      ({@link ContainerLabels#label}), and inspect, the vine and uproot find the
 *                      plot's containers by that label, so it must be the real plot id
 * @param runtime       the container runtime whose CLI the channel invokes
 */
public record ExecTarget(CommandRunner runner, String workspacePath, UUID targetId, ContainerRuntime runtime) {}
