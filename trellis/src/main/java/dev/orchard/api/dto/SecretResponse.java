package dev.orchard.api.dto;

import dev.orchard.core.model.Secret;

import java.time.Instant;

public record SecretResponse(String name, Instant createdAt, Instant updatedAt, Instant rotatedAt) {
    public static SecretResponse fromModel(Secret secret) {
        return new SecretResponse(secret.name(), secret.createdAt(), secret.updatedAt(), secret.rotatedAt());
    }
}
