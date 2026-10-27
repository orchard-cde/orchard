package dev.orchard.api.service;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.SeedSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.SshPublicKey;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayGroveServiceTest {

    private static final String KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";

    @Mock private GroveService groveService;
    @Mock private SshPublicKeyService sshPublicKeyService;
    @Mock private CultivatorService cultivatorService;

    private GatewayGroveService service;

    @BeforeEach
    void setUp() {
        service = new GatewayGroveService(groveService, sshPublicKeyService, cultivatorService);
    }

    private static Grove routableGrove(UUID groveId, UUID cultivatorId) {
        VmPlot plot = new VmPlot(
            UUID.randomUUID(), groveId, "qemu", "inst-1", PlotState.SAPLING, Instant.now(), Instant.now(),
            "127.0.0.1", 22, List.of(), VmSpec.small()
        );
        // Grove.plant() always mints its own random id, which would leave this grove's id
        // different from groveId (what the test queries getGrove(groveId) with) — construct
        // the record directly so route.get().groveId() actually equals groveId.
        return new Grove(
            groveId, cultivatorId, "demo", "https://github.com/org/demo", "main", null,
            GroveState.FLOURISHING, SeedSpec.AUTO, plot, List.of(), Instant.now(), Instant.now()
        );
    }

    private static Cultivator cultivator(UUID id, String email) {
        return new Cultivator(id, email, email, "google", "goog-" + id, null, null, Instant.now(), Instant.now());
    }

    @Test
    void resolveRoute_returnsRouteForRoutableGrove() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));

        var route = service.resolveRoute(groveId);

        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
        assertThat(route.get().cultivatorId()).isEqualTo(cultivatorId);
        assertThat(route.get().host()).isEqualTo("127.0.0.1");
        assertThat(route.get().sshPort()).isEqualTo(22);
        assertThat(route.get().state()).isEqualTo("FLOURISHING");
    }

    @Test
    void resolveRoute_mapsHostAndSshPort() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, UUID.randomUUID())));

        var route = service.resolveRoute(groveId).orElseThrow();

        assertThat(route.host()).isEqualTo("127.0.0.1");
        assertThat(route.sshPort()).isEqualTo(22);
    }

    private static Grove groveWith(GroveState state, Plot plot) {
        UUID groveId = UUID.randomUUID();
        return new Grove(
            groveId, UUID.randomUUID(), "demo", "https://github.com/org/demo", "main", null,
            state, SeedSpec.AUTO, plot, List.of(), Instant.now(), Instant.now()
        );
    }

    private static VmPlot vmPlot(PlotState state, String host) {
        return new VmPlot(
            UUID.randomUUID(), UUID.randomUUID(), "qemu", "inst-1", state, Instant.now(), Instant.now(),
            host, 22, List.of(), VmSpec.small()
        );
    }

    @Test
    void isRoutable_containerPlot_false() {
        ContainerPlot container = new ContainerPlot(
            UUID.randomUUID(), UUID.randomUUID(), "docker", "c-1", PlotState.SAPLING, Instant.now(), Instant.now(),
            null, ContainerRuntime.DOCKER, new ContainerSpec(2, 2048)
        );
        assertThat(service.isRoutable(groveWith(GroveState.FLOURISHING, container))).isFalse();
    }

    @Test
    void isRoutable_dormantGroveWithSaplingVmPlot_false() {
        assertThat(service.isRoutable(groveWith(GroveState.DORMANT, vmPlot(PlotState.SAPLING, "127.0.0.1")))).isFalse();
    }

    @Test
    void isRoutable_flourishingSaplingVmPlotWithHost_true() {
        assertThat(service.isRoutable(groveWith(GroveState.FLOURISHING, vmPlot(PlotState.SAPLING, "127.0.0.1")))).isTrue();
    }

    @Test
    void isRoutable_flourishingWitheredVmPlot_false() {
        assertThat(service.isRoutable(groveWith(GroveState.FLOURISHING, vmPlot(PlotState.WITHERED, "127.0.0.1")))).isFalse();
    }

    @Test
    void isRoutable_flourishingNullHost_false() {
        assertThat(service.isRoutable(groveWith(GroveState.FLOURISHING, vmPlot(PlotState.SAPLING, null)))).isFalse();
    }

    @Test
    void resolveRoute_emptyForUnknownGrove() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.empty());

        assertThat(service.resolveRoute(groveId)).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenPlotMissing() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withState(GroveState.PLANTING);
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove));

        assertThat(service.resolveRoute(grove.id())).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenPlotNotSapling() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        VmPlot germinating = VmPlot.germinate(groveId, "qemu", SeedlingSpec.from(VmSpec.small()));
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withPlot(germinating)
            .withState(GroveState.GROWING);
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(grove));

        assertThat(service.resolveRoute(groveId)).isEmpty();
    }

    @Test
    void exists_delegatesToGroveService() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.empty());
        assertThat(service.exists(groveId)).isFalse();

        when(groveService.getGrove(groveId))
            .thenReturn(Optional.of(routableGrove(groveId, UUID.randomUUID())));
        assertThat(service.exists(groveId)).isTrue();
    }

    @Test
    void listKeys_delegatesAndMaps() {
        UUID cultivatorId = UUID.randomUUID();
        SshPublicKey key = SshPublicKey.register(cultivatorId, "laptop", KEY);
        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of(key));

        var keys = service.listKeys(cultivatorId);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).id()).isEqualTo(key.id());
        assertThat(keys.get(0).name()).isEqualTo("laptop");
        assertThat(keys.get(0).publicKey()).isEqualTo(KEY);
        assertThat(keys.get(0).fingerprint()).isEqualTo(key.fingerprint());
    }

    @Test
    void authorizeOwner_allowsOwnerEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("alice@example.com"))
            .thenReturn(Optional.of(cultivator(cultivatorId, "alice@example.com")));

        var route = service.authorizeOwner(groveId, "alice@example.com");

        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
    }

    @Test
    void authorizeOwner_rejectsUnknownEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.authorizeOwner(groveId, "nobody@example.com")).isEmpty();
    }

    @Test
    void authorizeOwner_rejectsNonOwnerEmail() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(routableGrove(groveId, cultivatorId)));
        when(cultivatorService.findByEmail("bob@example.com"))
            .thenReturn(Optional.of(cultivator(UUID.randomUUID(), "bob@example.com")));

        assertThat(service.authorizeOwner(groveId, "bob@example.com")).isEmpty();
    }

    @Test
    void authorizeOwner_rejectsNotRoutableGrove() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withState(GroveState.PLANTING);
        when(groveService.getGrove(groveId)).thenReturn(Optional.of(grove));

        assertThat(service.authorizeOwner(groveId, "alice@example.com")).isEmpty();
    }
}
