package dev.orchard.core.model;

/**
 * What a seedling needs from the plot it is planted into. Carries the plot configuration today;
 * seedling metadata belongs here too.
 */
public record SeedlingSpec(
    String machineType,
    int cpuCores,
    int memoryMb,
    int diskGb,
    String serialOutput
) {
    public static SeedlingSpec of(String machineType, PlotResources resources, String serialOutput) {
        return new SeedlingSpec(machineType, resources.cpuCores(), resources.memoryMb(), resources.diskGb(),
            serialOutput);
    }

    public static SeedlingSpec from(VmSpec spec) {
        return new SeedlingSpec(spec.machineType(), spec.cpuCores(), spec.memoryMb(), spec.diskGb(),
            spec.serialOutput());
    }

    public static SeedlingSpec from(Plot plot) {
        return switch (plot) {
            case VmPlot vm -> from(vm.spec());
            case ContainerPlot container ->
                new SeedlingSpec(null, container.spec().cpuCores(), container.spec().memoryMb(), 0, null);
        };
    }
}
