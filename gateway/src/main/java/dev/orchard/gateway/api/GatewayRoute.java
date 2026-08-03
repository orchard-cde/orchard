package dev.orchard.gateway.api;

import java.util.UUID;

/** Route trellis returns for a routable (running-seedling) grove. Mirrors trellis's GatewayGroveResponse. */
public record GatewayRoute(
    UUID groveId,
    UUID cultivatorId,
    String seedlingIp,
    int seedlingPort,
    String state
) {}
