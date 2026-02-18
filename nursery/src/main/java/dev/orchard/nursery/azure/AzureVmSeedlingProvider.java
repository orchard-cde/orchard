package dev.orchard.nursery.azure;

import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Azure VM implementation of SeedlingProvider.
 * Provisions Azure Virtual Machines as Seedlings using cloud-init for initial setup.
 *
 * <p>TODO: Implement using Azure Resource Manager Compute
 * (com.azure.resourcemanager:azure-resourcemanager-compute).
 */
public class AzureVmSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureVmSeedlingProvider.class);
    private static final String PROVIDER_ID = "azure-vm";

    private final AzureConfig config;

    public AzureVmSeedlingProvider(AzureConfig config) {
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("Azure VM provider is registered but not yet implemented");
        return false;
    }
}
