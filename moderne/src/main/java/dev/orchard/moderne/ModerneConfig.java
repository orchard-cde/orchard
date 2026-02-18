package dev.orchard.moderne;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Moderne SaaS integration.
 * When an API key is provided, the platform can search and retrieve recipes
 * from the Moderne API. Without a key, the built-in recipe catalog is used.
 */
@ConfigurationProperties(prefix = "orchard.moderne")
public record ModerneConfig(
    String apiBaseUrl,
    String apiKey
) {
    /**
     * Returns true if a Moderne API key is configured, enabling the live API client.
     */
    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
