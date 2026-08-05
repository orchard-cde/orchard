package dev.orchard.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Owner-token auth: proves an email owns a grove before the gateway routes to it. */
public record AuthorizeOwnerRequest(
    @NotNull UUID groveId,
    @NotBlank @Email String email
) {}
