package dev.orchard.trellis;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.GroveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroveReconcilerTest {

    @Mock private GroveRepository groveRepository;
    @Mock private ProviderRegistry registry;
    @Mock private GroveProvider provider;

    /**
     * A grove in CLEARING means teardown started and did not finish. The reconciler attempts no
     * teardown, so it must not report success — ORPHANED preserves the row for operator action.
     * A future change may upgrade this to a provider-owned re-attempt (#228).
     */
    @Test
    void interruptedTeardownIsMarkedOrphanedNotCleared() {
        Grove grove = Grove
            .plant(UUID.randomUUID(), "interrupted", "https://example.invalid/r.git", "main")
            .withState(GroveState.CLEARING);
        GroveEntity entity = GroveEntity.fromModel(grove);

        when(groveRepository.findActiveGroves()).thenReturn(List.of());
        when(groveRepository.findByState(GroveState.PREPARING)).thenReturn(List.of());
        when(groveRepository.findByState(GroveState.CLEARING)).thenReturn(List.of(entity));

        new GroveReconciler(groveRepository, registry).run(null);

        assertThat(entity.getState()).isEqualTo(GroveState.ORPHANED);
        assertThat(entity.getState()).isNotEqualTo(GroveState.CLEARED);
    }

    private VmPlot closedPortPlot(String providerId) {
        return new VmPlot(UUID.randomUUID(), UUID.randomUUID(), providerId, "i-1", PlotState.SAPLING,
            Instant.now(), Instant.now(), "127.0.0.1", 1, List.of(), null);
    }

    private GroveEntity flourishing(VmPlot plot) {
        Grove grove = Grove
            .plant(UUID.randomUUID(), "g", "https://example.invalid/r.git", "main")
            .withState(GroveState.FLOURISHING)
            .withPlot(plot);
        return GroveEntity.fromModel(grove);
    }

    private void reconcile(GroveEntity entity) {
        when(groveRepository.findActiveGroves()).thenReturn(List.of(entity));
        when(groveRepository.findByState(GroveState.PREPARING)).thenReturn(List.of());
        when(groveRepository.findByState(GroveState.CLEARING)).thenReturn(List.of());
        new GroveReconciler(groveRepository, registry).run(null);
    }

    @Test
    void flourishing_inspectSapling_staysFlourishing() {
        VmPlot plot = closedPortPlot("owner");
        GroveEntity entity = flourishing(plot);
        when(registry.require("owner")).thenReturn(provider);
        when(provider.inspect(any())).thenReturn(CompletableFuture.completedFuture(plot));

        reconcile(entity);

        assertThat(entity.getState()).isEqualTo(GroveState.FLOURISHING);
    }

    @Test
    void flourishing_inspectWithered_blighted() {
        VmPlot plot = closedPortPlot("owner");
        GroveEntity entity = flourishing(plot);
        when(registry.require("owner")).thenReturn(provider);
        when(provider.inspect(any()))
            .thenReturn(CompletableFuture.completedFuture(plot.withState(PlotState.WITHERED)));

        reconcile(entity);

        assertThat(entity.getState()).isEqualTo(GroveState.BLIGHTED);
    }

    @Test
    void flourishing_unregisteredProvider_blighted() {
        GroveEntity entity = flourishing(closedPortPlot("gone"));
        when(registry.require("gone")).thenThrow(new IllegalStateException("no provider gone"));

        reconcile(entity);

        assertThat(entity.getState()).isEqualTo(GroveState.BLIGHTED);
    }

    @Test
    void flourishing_inspectNeverCompletes_blightedAfterTimeout() {
        GroveEntity entity = flourishing(closedPortPlot("owner"));
        when(registry.require("owner")).thenReturn(provider);
        when(provider.inspect(any())).thenReturn(new CompletableFuture<>());
        when(groveRepository.findActiveGroves()).thenReturn(List.of(entity));
        when(groveRepository.findByState(GroveState.PREPARING)).thenReturn(List.of());
        when(groveRepository.findByState(GroveState.CLEARING)).thenReturn(List.of());

        new GroveReconciler(groveRepository, registry, Duration.ofMillis(20)).run(null);

        assertThat(entity.getState()).isEqualTo(GroveState.BLIGHTED);
    }

    @Test
    void flourishing_inspectThrows_blighted() {
        GroveEntity entity = flourishing(closedPortPlot("owner"));
        when(registry.require("owner")).thenReturn(provider);
        when(provider.inspect(any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        reconcile(entity);

        assertThat(entity.getState()).isEqualTo(GroveState.BLIGHTED);
    }
}
