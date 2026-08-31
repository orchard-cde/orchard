package dev.orchard.nursery.azure;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.nursery.AbstractGroveProvider;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.PlantedSeedling;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Azure VM implementation of {@link GroveProvider}.
 * Provisions Azure Virtual Machines as Seedlings using cloud-init for initial setup.
 *
 * <p>Extends {@link AbstractGroveProvider} so the class hierarchy is already correct once this
 * provider is implemented — only the template methods below need filling in.
 *
 * <p>TODO: Implement using Azure Resource Manager Compute
 * (com.azure.resourcemanager:azure-resourcemanager-compute).
 */
public class AzureVmGroveProvider extends AbstractGroveProvider<Void> {

    private static final Logger log = LoggerFactory.getLogger(AzureVmGroveProvider.class);
    private static final String PROVIDER_ID = "azure-vm";

    /** Unread until this provider is implemented — kept so the wiring is already correct then. */
    private final AzureConfig config;

    public AzureVmGroveProvider(AzureConfig config, FruitGrower fruitGrower) {
        super(Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    protected Void launch(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    protected PlantedSeedling resolveEndpoint(Seedling seedling, Void launched) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
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

    // The overrides below exist only to keep an unimplemented provider from doing real work via
    // AbstractGroveProvider's working implementations. Delete them once this provider is implemented.

    @Override
    public CompletableFuture<Fruit> growFruit(Seedling seedling, Fruit fruit) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> compostFruit(Seedling seedling, Fruit fruit) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public Vine vine(Seedling seedling) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public void verifyDevcontainerCli(Seedling seedling, String expectedVersion) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("Azure VM provider is registered but not yet implemented");
        return false;
    }
}
