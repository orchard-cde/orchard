package dev.orchard.api.dto;

import java.util.Map;
import java.util.UUID;

public record SwarmStatusResponse(
    UUID groveId,
    int totalBees,
    Map<String, Integer> byState
) {}
