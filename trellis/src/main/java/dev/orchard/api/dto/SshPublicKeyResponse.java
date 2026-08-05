package dev.orchard.api.dto;

import dev.orchard.core.model.SshPublicKey;

import java.time.Instant;
import java.util.UUID;

public record SshPublicKeyResponse(
    UUID id,
    String name,
    String publicKey,
    String fingerprint,
    Instant createdAt
) {
    public static SshPublicKeyResponse fromModel(SshPublicKey key) {
        return new SshPublicKeyResponse(
            key.id(), key.name(), key.publicKey(), key.fingerprint(), key.createdAt());
    }
}
