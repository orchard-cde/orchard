package dev.orchard.nursery.azure;

import dev.orchard.core.model.Plot;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.AbstractVmGroveProvider;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.vine.Vine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Azure VM implementation of {@link GroveProvider}.
 * Provisions Azure Virtual Machines as Seedlings using cloud-init for initial setup.
 *
 * <p>Extends {@link AbstractVmGroveProvider} so the class hierarchy is already correct once this
 * provider is implemented — only the template methods below need filling in.
 *
 * <p>TODO: Implement using Azure Resource Manager Compute
 * (com.azure.resourcemanager:azure-resourcemanager-compute).
 */
public class AzureVmGroveProvider extends AbstractVmGroveProvider<Void> {

    private static final Logger log = LoggerFactory.getLogger(AzureVmGroveProvider.class);

    /** Unread until this provider is implemented — kept so the wiring is already correct then. */
    private final AzureConfig config;

    public AzureVmGroveProvider(AzureConfig config, FruitGrower fruitGrower) {
        super(Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
    }

    @Override
    public ProviderType type() {
        return ProviderType.AZURE_VM;
    }

    @Override
    protected Void launch(VmPlot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    protected VmEndpoint resolveEndpoint(VmPlot plot, Void launched) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> water(Plot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> uproot(Plot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    protected Vine vineFor(VmPlot plot) {
        throw new UnsupportedOperationException("Azure VM provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("Azure VM provider is registered but not yet implemented");
        return false;
    }
}
