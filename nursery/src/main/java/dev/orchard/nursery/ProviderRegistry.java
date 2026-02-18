package dev.orchard.nursery;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that manages available SeedlingProviders (VM providers).
 * Allows lookup by provider ID and designates a default provider.
 */
public class ProviderRegistry {

    private final Map<String, SeedlingProvider> providers = new ConcurrentHashMap<>();
    private String defaultProviderId;

    /**
     * Registers a SeedlingProvider in the registry.
     */
    public void register(SeedlingProvider provider) {
        providers.put(provider.getProviderId(), provider);
        if (defaultProviderId == null) {
            defaultProviderId = provider.getProviderId();
        }
    }

    /**
     * Sets the default provider by ID.
     *
     * @throws IllegalArgumentException if no provider with the given ID is registered
     */
    public void setDefault(String providerId) {
        if (!providers.containsKey(providerId)) {
            throw new IllegalArgumentException("No provider registered with ID: " + providerId);
        }
        this.defaultProviderId = providerId;
    }

    /**
     * Returns the default SeedlingProvider.
     *
     * @throws IllegalStateException if no providers are registered
     */
    public SeedlingProvider getDefault() {
        if (defaultProviderId == null || !providers.containsKey(defaultProviderId)) {
            throw new IllegalStateException("No default seedling provider configured");
        }
        return providers.get(defaultProviderId);
    }

    /**
     * Returns a provider by its ID.
     */
    public Optional<SeedlingProvider> get(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    /**
     * Returns all registered provider IDs.
     */
    public Collection<String> getProviderIds() {
        return providers.keySet();
    }

    /**
     * Returns the default provider ID.
     */
    public String getDefaultProviderId() {
        return defaultProviderId;
    }

    /**
     * Checks if a provider with the given ID is registered.
     */
    public boolean hasProvider(String providerId) {
        return providers.containsKey(providerId);
    }
}
