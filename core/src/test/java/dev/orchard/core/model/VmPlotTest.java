package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VmPlotTest {

    private static final SeedlingSpec SENTINEL =
        new SeedlingSpec("sentinel-type", 3, 5000, 77, "sentinel-serial");

    @Test
    void germinate_mapsEveryRequestFieldIntoTheSpec() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "p", SENTINEL);

        assertThat(plot.spec().machineType()).isEqualTo("sentinel-type");
        assertThat(plot.spec().cpuCores()).isEqualTo(3);
        assertThat(plot.spec().memoryMb()).isEqualTo(5000);
        assertThat(plot.spec().diskGb()).isEqualTo(77);
        assertThat(plot.spec().serialOutput()).isEqualTo("sentinel-serial");
    }

    @Test
    void germinate_mintsAFreshIdEachCall() {
        UUID groveId = UUID.randomUUID();

        assertThat(VmPlot.germinate(groveId, "p", SENTINEL).id())
            .isNotNull()
            .isNotEqualTo(VmPlot.germinate(groveId, "p", SENTINEL).id());
    }

    @Test
    void withState_sapling_setsReadyAt() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "p", SENTINEL);

        assertThat(plot.withState(PlotState.ROOTED).readyAt()).isNotNull();
    }

    @Test
    void withState_otherState_preservesReadyAt() {
        Instant readyAt = Instant.parse("2026-01-01T00:00:00Z");
        VmPlot plot = new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "p", "i-1",
            PlotState.ROOTED, Instant.now(), readyAt, "10.0.0.1", 22, List.of(), VmSpec.small());

        assertThat(plot.withState(PlotState.WILTING).readyAt()).isEqualTo(readyAt);
    }

    @Test
    void withEndpoint_setsIdentityAndAddress_preservingEverythingElse() {
        VmPlot plot = new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "p", null,
            PlotState.GERMINATING, Instant.now(), null, null, 22,
            List.of("ssh-ed25519 AAAA k"), VmSpec.medium());

        VmPlot located = plot.withEndpoint(new VmEndpoint(new ProviderHandle("i-abc"), new Host("10.1.2.3"), 2222));

        assertThat(located.providerInstanceId()).isEqualTo("i-abc");
        assertThat(located.host()).isEqualTo("10.1.2.3");
        assertThat(located.sshPort()).isEqualTo(2222);
        assertThat(located).usingRecursiveComparison()
            .ignoringFields("providerInstanceId", "host", "sshPort")
            .isEqualTo(plot);
    }

    @Test
    void constructor_nullProviderId_throws() {
        assertThatThrownBy(() -> new VmPlot(UUID.randomUUID(), UUID.randomUUID(), null, null,
            PlotState.GERMINATING, Instant.now(), null, null, 22, List.of(), VmSpec.small()))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("providerId");
    }

    @Test
    void constructor_nullAuthorizedKeys_becomesEmpty() {
        VmPlot plot = new VmPlot(UUID.randomUUID(), UUID.randomUUID(), "p", null,
            PlotState.GERMINATING, Instant.now(), null, null, 22, null, VmSpec.small());

        assertThat(plot.authorizedKeys()).isEmpty();
    }

    @Test
    void withAuthorizedKeys_replacesKeys() {
        VmPlot plot = VmPlot.germinate(UUID.randomUUID(), "p", SENTINEL);

        VmPlot keyed = plot.withAuthorizedKeys(List.of("ssh-ed25519 AAAA k"));

        assertThat(keyed.authorizedKeys()).containsExactly("ssh-ed25519 AAAA k");
        assertThat(keyed).usingRecursiveComparison().ignoringFields("authorizedKeys").isEqualTo(plot);
    }
}
