package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedSpecTest {

    @Test
    void nullOrBlankDefaultsToAuto() {
        assertThat(SeedSpec.fromFlag(null)).isEqualTo(SeedSpec.AUTO);
        assertThat(SeedSpec.fromFlag("")).isEqualTo(SeedSpec.AUTO);
        assertThat(SeedSpec.fromFlag("   ")).isEqualTo(SeedSpec.AUTO);
    }

    @Test
    void parsesKnownValuesCaseInsensitively() {
        assertThat(SeedSpec.fromFlag("auto")).isEqualTo(SeedSpec.AUTO);
        assertThat(SeedSpec.fromFlag("devcontainer")).isEqualTo(SeedSpec.DEVCONTAINER);
        assertThat(SeedSpec.fromFlag("DevFile")).isEqualTo(SeedSpec.DEVFILE);
        assertThat(SeedSpec.fromFlag("  DEVCONTAINER  ")).isEqualTo(SeedSpec.DEVCONTAINER);
    }

    @Test
    void rejectsUnknownValue() {
        assertThatThrownBy(() -> SeedSpec.fromFlag("k8s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k8s");
    }
}
