package dev.orchard.core.model;

public record VmSpec(
    int cpuCores,
    int memoryMb,
    int diskGb,
    String machineType,
    String serialOutput
) {
    public static VmSpec of(String machineType, PlotResources resources) {
        return new VmSpec(resources.cpuCores(), resources.memoryMb(), resources.diskGb(), machineType, null);
    }

    public static VmSpec small() {
        return of("small", PlotSizes.DEFAULT.small());
    }

    public static VmSpec medium() {
        return of("medium", PlotSizes.DEFAULT.medium());
    }

    public static VmSpec large() {
        return of("large", PlotSizes.DEFAULT.large());
    }
}
