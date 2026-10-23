package dev.orchard.api.dto;

import java.util.UUID;

/** Route info the SSH gateway needs to reach a grove's VM plot. */
public record GatewayGroveResponse(
    UUID groveId,
    UUID cultivatorId,
    String host,
    int sshPort,
    String state
) {}
