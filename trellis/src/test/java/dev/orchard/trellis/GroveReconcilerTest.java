package dev.orchard.trellis;

import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.GroveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroveReconcilerTest {

    @Mock private GroveRepository groveRepository;

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

        new GroveReconciler(groveRepository).run(null);

        assertThat(entity.getState()).isEqualTo(GroveState.ORPHANED);
        assertThat(entity.getState()).isNotEqualTo(GroveState.CLEARED);
    }
}
