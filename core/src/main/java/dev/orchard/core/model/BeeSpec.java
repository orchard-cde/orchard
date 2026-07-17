package dev.orchard.core.model;

import java.util.Map;

public record BeeSpec(
    BeeType type,
    String version,
    Map<String, String> configOverrides
) {
    public BeeSpec {
        configOverrides = configOverrides != null ? Map.copyOf(configOverrides) : Map.of();
    }

    public static BeeSpec of(BeeType type) {
        return new BeeSpec(type, null, Map.of());
    }

    public static BeeSpec of(BeeType type, String version) {
        return new BeeSpec(type, version, Map.of());
    }
}
