package dev.orchard.api.dto;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;

import java.util.UUID;

/** The API view of a grove's {@link Plot}; never carries authorized keys or the provider's handle. */
public record PlotInfo(
    PlotKind kind,
    UUID id,
    PlotState state,
    String providerId,
    String host,
    Integer sshPort,
    Integer cpuCores,
    Integer memoryMb,
    Integer diskGb,
    ContainerRuntime runtime
) {
    public static PlotInfo from(Plot plot) {
        return switch (plot) {
            case VmPlot vm -> new PlotInfo(PlotKind.VM, vm.id(), vm.state(), vm.providerId(),
                vm.host(), vm.sshPort(),
                vm.spec() == null ? null : vm.spec().cpuCores(),
                vm.spec() == null ? null : vm.spec().memoryMb(),
                vm.spec() == null ? null : vm.spec().diskGb(),
                null);
            case ContainerPlot container -> new PlotInfo(PlotKind.CONTAINER, container.id(),
                container.state(), container.providerId(), null, null,
                container.spec() == null ? null : container.spec().cpuCores(),
                container.spec() == null ? null : container.spec().memoryMb(),
                null, container.runtime());
        };
    }
}
