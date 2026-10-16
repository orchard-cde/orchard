package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlotRequestTest {

    @Test
    void from_copiesEveryVmSpecField() {
        VmSpec spec = new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial");

        assertThat(PlotRequest.from(spec))
            .isEqualTo(new PlotRequest("sentinel-type", 3, 5000, 77, "sentinel-serial"));
    }
}
