package dev.orchard.core.model;

public record VmSpec(
    int cpuCores,
    int memoryMb,
    int diskGb,
    String machineType,
    String serialOutput
) {
    public static VmSpec small() {
        return new VmSpec(2, 4096, 20, "small", null);
    }

    public static VmSpec medium() {
        return new VmSpec(4, 8192, 40, "medium", null);
    }

    public static VmSpec large() {
        return new VmSpec(8, 16384, 80, "large", null);
    }
}
