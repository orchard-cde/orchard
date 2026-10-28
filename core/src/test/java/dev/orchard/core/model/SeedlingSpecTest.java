package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedlingSpecTest {

    @Test
    void from_copiesEveryVmSpecField() {
        VmSpec spec = new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial");

        assertThat(SeedlingSpec.from(spec))
            .isEqualTo(new SeedlingSpec("sentinel-type", 3, 5000, 77, "sentinel-serial"));
    }
}
