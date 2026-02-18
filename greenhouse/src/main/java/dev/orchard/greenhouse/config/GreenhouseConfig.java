package dev.orchard.greenhouse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Greenhouse module - the prebuild and image caching system.
 * Configures the container registry, credentials, and scheduling parameters.
 */
@ConfigurationProperties(prefix = "orchard.greenhouse")
public record GreenhouseConfig(
    @DefaultValue("localhost:5000") String registryUrl,
    @DefaultValue("") String username,
    @DefaultValue("") String password,
    @DefaultValue("false") boolean prebuildEnabled,
    @DefaultValue("60") int prebuildIntervalMinutes,
    @DefaultValue("/tmp/orchard/greenhouse") String workDir
) {
    /**
     * Returns the full image reference for a given image name (prepends registry URL).
     */
    public String imageReference(String imageName) {
        if (registryUrl == null || registryUrl.isBlank()) {
            return imageName;
        }
        return registryUrl + "/" + imageName;
    }
}
