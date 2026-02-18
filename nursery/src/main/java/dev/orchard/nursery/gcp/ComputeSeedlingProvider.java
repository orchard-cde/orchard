package dev.orchard.nursery.gcp;

import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.SeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * GCP Compute Engine implementation of SeedlingProvider.
 * Provisions GCE instances as Seedlings using startup scripts for initial setup.
 *
 * <p>TODO: Implement using Google Cloud Compute v1 client (com.google.cloud:google-cloud-compute).
 */
public class ComputeSeedlingProvider implements SeedlingProvider {

    private static final Logger log = LoggerFactory.getLogger(ComputeSeedlingProvider.class);
    private static final String PROVIDER_ID = "gcp-compute";

    private final ComputeConfig config;

    public ComputeSeedlingProvider(ComputeConfig config) {
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plant(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> water(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> dormant(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> uproot(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Seedling> inspect(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("GCP Compute provider is registered but not yet implemented");
        return false;
    }
}
