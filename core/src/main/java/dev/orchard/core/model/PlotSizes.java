package dev.orchard.core.model;

/**
 * The machine-size tiers a provider offers. The defaults are the built-in tiers; a provider whose
 * substrate sizes differently overrides only the tiers that differ.
 */
public interface PlotSizes {

    PlotSizes DEFAULT = new PlotSizes() {};

    default PlotResources small() {
        return new PlotResources(2, 4096, 20);
    }

    default PlotResources medium() {
        return new PlotResources(4, 8192, 40);
    }

    default PlotResources large() {
        return new PlotResources(8, 16384, 80);
    }

    default PlotResources custom(int cpuCores, int memoryMb, int diskGb) {
        return new PlotResources(cpuCores, memoryMb, diskGb);
    }
}
