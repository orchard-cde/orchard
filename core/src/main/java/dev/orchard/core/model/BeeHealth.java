package dev.orchard.core.model;

import java.time.Instant;

public record BeeHealth(
    boolean alive,
    boolean responsive,
    String currentActivity,
    Instant lastCheckedAt
) {
    public static BeeHealth unknown() {
        return new BeeHealth(false, false, null, null);
    }
}
