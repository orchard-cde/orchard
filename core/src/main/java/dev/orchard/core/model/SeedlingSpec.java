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
}
