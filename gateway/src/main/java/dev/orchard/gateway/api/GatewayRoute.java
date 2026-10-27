package dev.orchard.gateway.api;

import java.util.UUID;

/** Route trellis returns for a routable (running VM plot) grove. Mirrors trellis's GatewayGroveResponse. */
public record GatewayRoute(
    UUID groveId,
    UUID cultivatorId,
    String host,
    int sshPort,
    String state
) {}
