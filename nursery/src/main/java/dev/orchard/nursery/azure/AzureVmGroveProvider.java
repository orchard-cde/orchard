package dev.orchard.nursery.azure;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Azure VM implementation of {@link GroveProvider}.
 * Provisions Azure Virtual Machines as Seedlings using cloud-init for initial setup.
 *
 * <p>Implements {@link GroveProvider} directly rather than extending
 * {@code AbstractGroveProvider}: there is no launch sequence to share yet, and the abstract
 * class's {@code plantSubstrate} would map the unimplemented steps to {@code BLIGHTED} instead of
 * failing loudly.
 *
 * <p>TODO: Implement using Azure Resource Manager Compute
 * (com.azure.resourcemanager:azure-resourcemanager-compute).
 */
public class AzureVmGroveProvider implements GroveProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureVmGroveProvider.class);
    private static final String PROVIDER_ID = "azure-vm";

    private final AzureConfig config;
    private final FruitGrower fruitGrower;

    public AzureVmGroveProvider(AzureConfig config, FruitGrower fruitGrower) {
        this.config = config;
        this.fruitGrower = fruitGrower;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public CompletableFuture<Seedling> plantSubstrate(Seedling seedling) {
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
    public CompletableFuture<Fruit> growFruit(Seedling seedling, Fruit fruit) {
        return fruitGrower.grow(seedling, fruit);
    }

    @Override
    public CompletableFuture<Void> compostFruit(Seedling seedling, Fruit fruit) {
        return fruitGrower.compost(seedling, fruit);
    }

    @Override
    public Vine vine(Seedling seedling) {
        return new SshVine(seedling);
    }

    @Override
    public void verifyDevcontainerCli(Seedling seedling, String expectedVersion) {
        GroveProvider.verifyDevcontainerCli(seedling, expectedVersion, vine(seedling).commands());
    }

    @Override
    public boolean isAvailable() {
        log.warn("Azure VM provider is registered but not yet implemented");
        return false;
    }
}
