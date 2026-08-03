package dev.orchard.api.service;

import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.SeedSpec;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.core.model.SshPublicKey;
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
        Seedling seedling = new Seedling(
            UUID.randomUUID(), groveId, "inst-1", "127.0.0.1", 22,
            SeedlingState.SAPLING, Seedling.SeedlingSpec.small(), Instant.now(), Instant.now()
        );
        // Grove.plant() always mints its own random id, which would leave this grove's id
        // different from groveId (what the test queries getGrove(groveId) with) — construct
        // the record directly so route.get().groveId() actually equals groveId.
        return new Grove(
            groveId, cultivatorId, "demo", "https://github.com/org/demo", "main", null,
            GroveState.FLOURISHING, SeedSpec.AUTO, seedling, List.of(), Instant.now(), Instant.now()
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
        assertThat(route.get().seedlingIp()).isEqualTo("127.0.0.1");
        assertThat(route.get().seedlingPort()).isEqualTo(22);
        assertThat(route.get().state()).isEqualTo("FLOURISHING");
    }

    @Test
    void resolveRoute_emptyForUnknownGrove() {
        UUID groveId = UUID.randomUUID();
        when(groveService.getGrove(groveId)).thenReturn(Optional.empty());

        assertThat(service.resolveRoute(groveId)).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenSeedlingMissing() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withState(GroveState.PLANTING);
        when(groveService.getGrove(grove.id())).thenReturn(Optional.of(grove));

        assertThat(service.resolveRoute(grove.id())).isEmpty();
    }

    @Test
    void resolveRoute_emptyWhenSeedlingNotSapLing() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        Seedling germinating = Seedling.germinate(groveId, Seedling.SeedlingSpec.small());
        Grove grove = Grove.plant(cultivatorId, "demo", "https://github.com/org/demo", "main")
            .withSeedling(germinating)
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
