package dev.orchard.api.dto;

import java.util.UUID;

/** Route info the SSH gateway needs to reach a grove's seedling. */
public record GatewayGroveResponse(
    UUID groveId,
    UUID cultivatorId,
    String seedlingIp,
    int seedlingPort,
    String state
) {}
