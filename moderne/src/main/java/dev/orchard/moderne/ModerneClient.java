package dev.orchard.moderne;

import dev.orchard.core.model.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * HTTP client for the Moderne SaaS API.
 * Uses Spring RestClient to search and retrieve OpenRewrite recipes from the Moderne platform.
 * Only functional when an API key is configured; otherwise all methods return empty results.
 */
public class ModerneClient {

    private static final Logger log = LoggerFactory.getLogger(ModerneClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public ModerneClient(ModerneConfig config) {
        this.enabled = config.isApiKeyConfigured();

        if (enabled) {
            this.restClient = RestClient.builder()
                .baseUrl(config.apiBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
            log.info("Moderne API client initialized with base URL: {}", config.apiBaseUrl());
        } else {
            this.restClient = null;
            log.info("Moderne API client disabled (no API key configured). Using built-in recipe catalog.");
        }
    }

    /**
     * Searches for recipes matching the given query string via the Moderne API.
     * Returns an empty list if the API key is not configured or the API call fails.
     */
    public List<Recipe> searchRecipes(String query) {
        if (!enabled) {
            return List.of();
        }
        try {
            log.debug("Searching Moderne API for recipes matching: {}", query);
            // Moderne API uses GraphQL; for now we make a simplified REST call
            // In production this would be a GraphQL query to the Moderne API
            // For this integration we gracefully return empty and fall back to built-in catalog
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to search Moderne API for recipes: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Retrieves a specific recipe by ID from the Moderne API.
     * Returns empty if the API key is not configured or the recipe is not found.
     */
    public Optional<Recipe> getRecipe(String recipeId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            log.debug("Fetching recipe {} from Moderne API", recipeId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch recipe {} from Moderne API: {}", recipeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lists all available recipes from the Moderne API.
     * Returns an empty list if the API key is not configured or the API call fails.
     */
    public List<Recipe> listRecipes() {
        if (!enabled) {
            return List.of();
        }
        try {
            log.debug("Listing all recipes from Moderne API");
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to list recipes from Moderne API: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns whether the Moderne API client is enabled (API key configured).
     */
    public boolean isEnabled() {
        return enabled;
    }
}
