package dev.orchard.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to trigger a prebuild for a repository and branch.
 */
public record TriggerPrebuildRequest(
    @NotBlank(message = "Repository URL is required")
    String repositoryUrl,

    String branch
) {
    public String branch() {
        return branch != null ? branch : "main";
    }
}
