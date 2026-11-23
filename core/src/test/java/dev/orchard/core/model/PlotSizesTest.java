package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlotSizesTest {

    @Test
    void defaultTiersHaveTheBuiltInResources() {
        assertThat(PlotSizes.DEFAULT.small()).isEqualTo(new PlotResources(2, 4096, 50));
        assertThat(PlotSizes.DEFAULT.medium()).isEqualTo(new PlotResources(4, 8192, 100));
        assertThat(PlotSizes.DEFAULT.large()).isEqualTo(new PlotResources(8, 16384, 200));
    }

    @Test
    void custom_returnsItsInputs() {
        assertThat(PlotSizes.DEFAULT.custom(3, 5000, 77)).isEqualTo(new PlotResources(3, 5000, 77));
    }

    @Test
    void overridingOneTier_leavesTheOthersAtTheirDefaults() {
        PlotSizes sizes = new PlotSizes() {
            @Override
            public PlotResources medium() {
                return new PlotResources(6, 6000, 60);
            }
        };

        assertThat(sizes.medium()).isEqualTo(new PlotResources(6, 6000, 60));
        assertThat(sizes.small()).isEqualTo(PlotSizes.DEFAULT.small());
        assertThat(sizes.large()).isEqualTo(PlotSizes.DEFAULT.large());
    }

    @Test
    void tiersDelegateToCustom_soOverridingCustomReshapesEveryTier() {
        PlotSizes noDisk = new PlotSizes() {
            @Override
            public PlotResources custom(int cpuCores, int memoryMb, int diskGb) {
                return new PlotResources(cpuCores, memoryMb, 0);
            }
        };

        assertThat(noDisk.small()).isEqualTo(new PlotResources(2, 4096, 0));
        assertThat(noDisk.medium()).isEqualTo(new PlotResources(4, 8192, 0));
        assertThat(noDisk.large()).isEqualTo(new PlotResources(8, 16384, 0));
    }

    @Test
    void vmSpecTiers_areBuiltFromTheDefaultCatalogue() {
        assertThat(VmSpec.small()).isEqualTo(new VmSpec(2, 4096, 50, "small", null));
        assertThat(VmSpec.medium()).isEqualTo(new VmSpec(4, 8192, 100, "medium", null));
        assertThat(VmSpec.large()).isEqualTo(new VmSpec(8, 16384, 200, "large", null));
        assertThat(VmSpec.of("medium", PlotSizes.DEFAULT.medium())).isEqualTo(VmSpec.medium());
    }
}
