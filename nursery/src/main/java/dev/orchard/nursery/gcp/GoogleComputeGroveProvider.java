package dev.orchard.nursery.gcp;

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
 * GCP Compute Engine implementation of {@link GroveProvider}.
 * Provisions GCE instances as Seedlings using startup scripts for initial setup.
 *
 * <p>Extends {@link AbstractVmGroveProvider} so the class hierarchy is already correct once this
 * provider is implemented — only the template methods below need filling in.
 *
 * <p>TODO: Implement using Google Cloud Compute v1 client (com.google.cloud:google-cloud-compute).
 */
public class GoogleComputeGroveProvider extends AbstractVmGroveProvider<Void> {

    private static final Logger log = LoggerFactory.getLogger(GoogleComputeGroveProvider.class);

    /** Unread until this provider is implemented — kept so the wiring is already correct then. */
    private final GoogleComputeConfig config;

    public GoogleComputeGroveProvider(GoogleComputeConfig config, FruitGrower fruitGrower) {
        super(Executors.newVirtualThreadPerTaskExecutor(), fruitGrower);
        this.config = config;
    }

    @Override
    public ProviderType type() {
        return ProviderType.GCP_COMPUTE;
    }

    @Override
    protected Void launch(VmPlot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    protected VmEndpoint resolveEndpoint(VmPlot plot, Void launched) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> water(Plot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> dormant(Plot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Void> uproot(Plot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public CompletableFuture<Plot> inspect(Plot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    protected Vine vineFor(VmPlot plot) {
        throw new UnsupportedOperationException("GCP Compute provider not yet implemented");
    }

    @Override
    public boolean isAvailable() {
        log.warn("GCP Compute provider is registered but not yet implemented");
        return false;
    }
}
