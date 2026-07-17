package dev.orchard.api.dto;

import dev.orchard.core.model.BeeType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateBeeRequest(
    @NotNull(message = "Bee type is required")
    BeeType beeType,
    String version,
    Map<String, String> configOverrides
) {
    public CreateBeeRequest {
        configOverrides = configOverrides != null ? Map.copyOf(configOverrides) : Map.of();
    }
}
