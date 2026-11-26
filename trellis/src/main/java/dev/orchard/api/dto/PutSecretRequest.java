package dev.orchard.api.dto;

import jakarta.validation.constraints.NotNull;

public record PutSecretRequest(@NotNull(message = "Value is required") String value) {}
