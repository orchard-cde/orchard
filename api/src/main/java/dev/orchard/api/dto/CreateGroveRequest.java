package dev.orchard.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateGroveRequest(
    @NotBlank(message = "Repository URL is required")
    String repositoryUrl,

    String branch,

    String name,

    String machineSize,

    String serialOutput,

    String spec
) {
    /**
     * Backward-compatible constructor for callers that predate the {@code spec} field.
     * Defaults {@code spec} to {@code null}, which resolves to
     * {@link dev.orchard.api.service.SeedSpec#AUTO}.
     */
    public CreateGroveRequest(String repositoryUrl, String branch, String name, String machineSize, String serialOutput) {
        this(repositoryUrl, branch, name, machineSize, serialOutput, null);
    }

    public String branch() {
        return branch != null ? branch : "main";
    }

    public String machineSize() {
        return machineSize != null ? machineSize : "small";
    }
}
