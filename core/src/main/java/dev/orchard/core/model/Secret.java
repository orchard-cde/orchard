package dev.orchard.core.model;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record Secret(
    UUID id,
    SecretScope scope,
    UUID cultivatorId,
    String owner,
    String name,
    String vaultKey,
    Instant createdAt,
    Instant updatedAt,
    Instant rotatedAt
) {
    public static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,254}");

    public static Secret store(UUID cultivatorId, String name, String vaultKey) {
        if (cultivatorId == null) {
            throw new IllegalArgumentException("cultivatorId must not be null");
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must match " + NAME.pattern());
        }
        if (vaultKey == null || vaultKey.isBlank()) {
            throw new IllegalArgumentException("vaultKey must not be blank");
        }
        Instant now = Instant.now();
        return new Secret(UUID.randomUUID(), SecretScope.CULTIVATOR, cultivatorId, null, name, vaultKey, now, now, now);
    }

    public Secret withValueWritten(String vaultKey, Instant at) {
        return new Secret(id, scope, cultivatorId, owner, name, vaultKey, createdAt, at, at);
    }
}
