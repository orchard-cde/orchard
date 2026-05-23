package dev.orchard.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateGroveRequest(
    @NotBlank(message = "Repository URL is required")
    String repositoryUrl,

    String branch,

    String name,

    String machineSize,

    String serialOutput
) {
    public String branch() {
        return branch != null ? branch : "main";
    }

    public String machineSize() {
        return machineSize != null ? machineSize : "small";
    }
}
