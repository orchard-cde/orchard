package dev.orchard.core.model;

import java.util.List;

/**
 * A Recipe represents an OpenRewrite recipe that can be applied to code in a Grove.
 * Recipes are sourced from the built-in catalog or the Moderne SaaS API.
 */
public record Recipe(
    String id,
    String name,
    String description,
    String category,
    List<String> tags,
    List<RecipeOption> options
) {
    /**
     * An option that can be configured when applying a recipe.
     */
    public record RecipeOption(
        String name,
        String type,
        String description,
        boolean required,
        String defaultValue
    ) {}
}
