package dev.orchard.moderne;

import dev.orchard.core.model.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for browsing and searching OpenRewrite recipes.
 * Tries the Moderne SaaS API first (if an API key is configured),
 * falling back to the built-in recipe catalog when the API is unavailable.
 */
@Service
public class RecipeCatalogService {

    private static final Logger log = LoggerFactory.getLogger(RecipeCatalogService.class);

    private final ModerneClient moderneClient;

    public RecipeCatalogService(ModerneClient moderneClient) {
        this.moderneClient = moderneClient;
    }

    /**
     * Lists all available recipes. Prefers the Moderne API if configured,
     * falls back to the built-in catalog.
     */
    public List<Recipe> listAll() {
        if (moderneClient.isEnabled()) {
            List<Recipe> apiRecipes = moderneClient.listRecipes();
            if (!apiRecipes.isEmpty()) {
                log.debug("Returning {} recipes from Moderne API", apiRecipes.size());
                return apiRecipes;
            }
            log.debug("Moderne API returned no recipes, falling back to built-in catalog");
        }
        return BuiltinRecipeCatalog.all();
    }

    /**
     * Searches for recipes matching the given query. Tries the Moderne API first,
     * falls back to the built-in catalog search.
     */
    public List<Recipe> searchRecipes(String query) {
        if (moderneClient.isEnabled()) {
            List<Recipe> apiResults = moderneClient.searchRecipes(query);
            if (!apiResults.isEmpty()) {
                log.debug("Found {} recipes from Moderne API matching '{}'", apiResults.size(), query);
                return apiResults;
            }
            log.debug("Moderne API returned no results for '{}', falling back to built-in catalog", query);
        }
        return BuiltinRecipeCatalog.search(query);
    }

    /**
     * Retrieves a specific recipe by its ID. Tries the Moderne API first,
     * falls back to the built-in catalog.
     */
    public Optional<Recipe> getRecipe(String recipeId) {
        if (moderneClient.isEnabled()) {
            Optional<Recipe> apiRecipe = moderneClient.getRecipe(recipeId);
            if (apiRecipe.isPresent()) {
                return apiRecipe;
            }
            log.debug("Recipe {} not found in Moderne API, checking built-in catalog", recipeId);
        }
        return BuiltinRecipeCatalog.findById(recipeId);
    }
}
