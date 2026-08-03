package dev.orchard.api.dto;

import java.util.UUID;

/** A cultivator's registered SSH public key, as seen by the SSH gateway. */
public record GatewayKeyResponse(
    UUID id,
    String name,
    String publicKey,
    String fingerprint
) {}
