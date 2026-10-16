package dev.orchard.core.model;

/** Substrate sizing a caller asks {@code GroveProvider.reserve} for. */
public record PlotRequest(
    String machineType,
    int cpuCores,
    int memoryMb,
    int diskGb,
    String serialOutput
) {
    public static PlotRequest from(VmSpec spec) {
        return new PlotRequest(spec.machineType(), spec.cpuCores(), spec.memoryMb(), spec.diskGb(),
            spec.serialOutput());
    }
}
