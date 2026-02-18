package dev.orchard.api.dto;

import dev.orchard.core.model.Recipe;

import java.util.List;

/**
 * API response for an OpenRewrite recipe.
 */
public record RecipeResponse(
    String id,
    String name,
    String description,
    String category,
    List<String> tags,
    List<RecipeOptionResponse> options
) {
    public record RecipeOptionResponse(
        String name,
        String type,
        String description,
        boolean required,
        String defaultValue
    ) {
        public static RecipeOptionResponse fromModel(Recipe.RecipeOption option) {
            return new RecipeOptionResponse(
                option.name(),
                option.type(),
                option.description(),
                option.required(),
                option.defaultValue()
            );
        }
    }

    public static RecipeResponse fromModel(Recipe recipe) {
        return new RecipeResponse(
            recipe.id(),
            recipe.name(),
            recipe.description(),
            recipe.category(),
            recipe.tags(),
            recipe.options() != null
                ? recipe.options().stream().map(RecipeOptionResponse::fromModel).toList()
                : List.of()
        );
    }
}
