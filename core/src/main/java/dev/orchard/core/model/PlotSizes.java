package dev.orchard.core.model;

/** A provider whose substrate sizes differently overrides only the tiers that differ. */
public interface PlotSizes {

    PlotSizes DEFAULT = new PlotSizes() {};

    default PlotResources small() {
        return custom(2, 4096, 20);
    }

    default PlotResources medium() {
        return custom(4, 8192, 40);
    }

    default PlotResources large() {
        return custom(8, 16384, 80);
    }

    default PlotResources custom(int cpuCores, int memoryMb, int diskGb) {
        return new PlotResources(cpuCores, memoryMb, diskGb);
    }
}
