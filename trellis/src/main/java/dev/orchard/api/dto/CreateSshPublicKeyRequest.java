package dev.orchard.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSshPublicKeyRequest(
    @NotBlank(message = "Name is required")
    String name,
    @NotBlank(message = "Public key is required")
    String publicKey
) {}
