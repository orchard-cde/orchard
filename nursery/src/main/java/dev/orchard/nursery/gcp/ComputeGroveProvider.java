package dev.orchard.nursery.gcp;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * GCP Compute Engine implementation of {@link GroveProvider}.
 * Provisions GCE instances as Seedlings using startup scripts for initial setup.
 *
 * <p>Implements {@link GroveProvider} directly rather than extending
 * {@code AbstractGroveProvider}: there is no launch sequence to share yet, and the abstract
 * class's {@code plantSubstrate} would map the unimplemented steps to {@code BLIGHTED} instead of
 * failing loudly.
 *
 * <p>TODO: Implement using Google Cloud Compute v1 client (com.google.cloud:google-cloud-compute).
 */
public class ComputeGroveProvider implements GroveProvider {

    private static final Logger log = LoggerFactory.getLogger(ComputeGroveProvider.class);
    private static final String PROVIDER_ID = "gcp-compute";

    /** Unread until this provider is implemented — kept so the wiring is already correct then. */
    private final ComputeConfig config;

    public ComputeGroveProvider(ComputeConfig config) {
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plantSubstrate(Seedling seedling) {
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
    public CompletableFuture<Fruit> growFruit(Seedling seedling, Fruit fruit) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> compostFruit(Seedling seedling, Fruit fruit) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public Vine vine(Seedling seedling) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public void verifyDevcontainerCli(Seedling seedling, String expectedVersion) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("GCP Compute provider is registered but not yet implemented");
        return false;
    }
}
