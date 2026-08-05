package dev.orchard.gateway.api;

import java.util.UUID;

/** A cultivator's registered SSH public key as trellis exposes it to the gateway. */
public record GatewayKey(
    UUID id,
    String name,
    String publicKey,
    String fingerprint
) {}
