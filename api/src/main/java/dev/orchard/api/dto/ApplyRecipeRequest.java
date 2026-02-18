package dev.orchard.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request to apply an OpenRewrite recipe to a Grove's codebase.
 */
public record ApplyRecipeRequest(
    @NotBlank(message = "Recipe ID is required")
    String recipeId,

    Map<String, String> options
) {
    public Map<String, String> options() {
        return options != null ? options : Map.of();
    }
}
