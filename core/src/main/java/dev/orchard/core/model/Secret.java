package dev.orchard.core.model;

import java.time.Instant;
import java.util.Set;
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
    public static final Set<String> RESERVED =
        Set.of("PATH", "IFS", "ENV", "BASH_ENV", "LD_PRELOAD", "LD_LIBRARY_PATH", "HOME", "SHELL");

    public static boolean isStorableName(String name) {
        return name != null && NAME.matcher(name).matches() && !RESERVED.contains(name);
    }

    /** Throws {@link IllegalArgumentException} unless {@code name} matches {@link #NAME} and is not {@link #RESERVED}. */
    public static void requireStorableName(String name) {
        if (!isStorableName(name)) {
            throw new IllegalArgumentException(name != null && RESERVED.contains(name)
                ? "reserved name: " + name
                : "name must match " + NAME.pattern());
        }
    }

    /** Throws {@link IllegalArgumentException} unless {@code name} matches {@link #NAME}. */
    public static void requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must match " + NAME.pattern());
        }
    }

    public static Secret store(UUID cultivatorId, String name, String vaultKey) {
        if (cultivatorId == null) {
            throw new IllegalArgumentException("cultivatorId must not be null");
        }
        requireStorableName(name);
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
