package dev.orchard.api.service;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.core.model.VmPlot;

/**
 * Converts between {@code Grove}'s {@link Seedling} and the provider seam's {@link Plot}. Exists
 * only while {@code Grove} still holds a {@code Seedling}; Plan 3b moves {@code Grove} onto
 * {@code Plot} and deletes this class.
 */
final class PlotBridge {

    private PlotBridge() {}

    static VmPlot toPlot(Seedling seedling, String providerId) {
        return new VmPlot(
            seedling.id(),
            seedling.groveId(),
            providerId,
            seedling.providerInstanceId(),
            seedling.state() == null ? null : PlotState.valueOf(seedling.state().name()),
            seedling.plantedAt(),
            seedling.readyAt(),
            seedling.ipAddress(),
            seedling.sshPort(),
            seedling.authorizedKeys(),
            seedling.spec()
        );
    }

    static Seedling toSeedling(Plot plot) {
        return switch (plot) {
            case VmPlot vm -> new Seedling(
                vm.id(),
                vm.groveId(),
                vm.providerInstanceId(),
                vm.host(),
                vm.sshPort(),
                vm.state() == null ? null : SeedlingState.valueOf(vm.state().name()),
                vm.spec(),
                vm.plantedAt(),
                vm.readyAt(),
                vm.authorizedKeys()
            );
            case ContainerPlot container -> throw new IllegalStateException(
                "Container plot " + container.id() + " cannot be held by a Grove until Plan 3b");
        };
    }
}
