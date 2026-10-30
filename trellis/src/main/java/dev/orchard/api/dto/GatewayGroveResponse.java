package dev.orchard.api.dto;

import dev.orchard.core.model.ContainerRuntime;

import java.util.UUID;

/**
 * Route info the SSH gateway needs to reach a grove. {@code kind} is {@code "vm"} (host and
 * sshPort set) or {@code "container"} (runtime, containerId, user and workspaceFolder set; user
 * and workspaceFolder may be null).
 */
public record GatewayGroveResponse(
    UUID groveId,
    UUID cultivatorId,
    String state,
    String kind,
    String host,
    Integer sshPort,
    ContainerRuntime runtime,
    String containerId,
    String user,
    String workspaceFolder
) {}
