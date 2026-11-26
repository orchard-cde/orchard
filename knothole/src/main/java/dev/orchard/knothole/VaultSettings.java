package dev.orchard.knothole;

import java.time.Duration;

/** Operational limits of a {@link FileSecretVault}; the cipher parameters are not adjustable. */
public record VaultSettings(int maxValueBytes, Duration staleTempAge) {

    public static final VaultSettings DEFAULTS = new VaultSettings(65536, Duration.ofSeconds(60));

    public VaultSettings {
        if (maxValueBytes <= 0) {
            throw new IllegalArgumentException("maxValueBytes must be positive");
        }
        if (staleTempAge.isNegative()) {
            throw new IllegalArgumentException("staleTempAge must not be negative");
        }
    }
}
