package dev.orchard.api.service;

import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.event.BeeStateChangedEvent;
import dev.orchard.apiary.BeeKeeper;
import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.core.model.*;
import dev.orchard.nursery.CommandRunner;
import dev.orchard.roots.entity.BeeEntity;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.BeeRepository;
import dev.orchard.roots.repository.GroveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BeeServiceTest {

    @Mock private BeeKeeperRegistry beeKeeperRegistry;
    @Mock private BeeRepository beeRepository;
    @Mock private GroveRepository groveRepository;
    @Mock private CredentialResolver credentialResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BeeService beeService;

    private final UUID groveId = UUID.randomUUID();
    private final UUID cultivatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        beeService = new BeeService(beeKeeperRegistry, beeRepository, groveRepository,
            credentialResolver, eventPublisher);
    }

    @Test
    void attachBee_groveNotFlourishing_throwsIllegalState() {
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(groveEntity.getState()).thenReturn(GroveState.PLANTING);

        CreateBeeRequest request = new CreateBeeRequest(BeeType.CLAUDE_CODE, null, null);

        assertThatThrownBy(() -> beeService.attachBee(groveId, cultivatorId, request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FLOURISHING");
    }

    @Test
    void attachBee_unregisteredBeeType_throwsIllegalArgument() {
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(groveEntity.getState()).thenReturn(GroveState.FLOURISHING);
        when(beeKeeperRegistry.get(BeeType.CLAUDE_CODE)).thenReturn(Optional.empty());

        CreateBeeRequest request = new CreateBeeRequest(BeeType.CLAUDE_CODE, null, null);

        assertThatThrownBy(() -> beeService.attachBee(groveId, cultivatorId, request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CLAUDE_CODE");
    }

    @Test
    void attachBee_persistsBeeInHatchingState() {
        setupFlourishingGrove();
        BeeKeeper keeper = setupRegisteredKeeper(BeeType.CLAUDE_CODE);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            CreateBeeRequest request = new CreateBeeRequest(BeeType.CLAUDE_CODE, "1.0", null);
            Bee result = beeService.attachBee(groveId, cultivatorId, request);

            assertThat(result.state()).isEqualTo(BeeState.HATCHING);
            assertThat(result.groveId()).isEqualTo(groveId);
            assertThat(result.type()).isEqualTo(BeeType.CLAUDE_CODE);

            ArgumentCaptor<BeeEntity> captor = ArgumentCaptor.forClass(BeeEntity.class);
            verify(beeRepository).save(captor.capture());
            assertThat(captor.getValue().getState()).isEqualTo(BeeState.HATCHING);
        }
    }

    @Test
    void attachBee_registersAfterCommitCallback() {
        setupFlourishingGrove();
        BeeKeeper keeper = setupRegisteredKeeper(BeeType.CLAUDE_CODE);
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        when(keeper.install(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(bee));

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    TransactionSynchronization sync = invocation.getArgument(0);
                    sync.afterCommit();
                    return null;
                });

            CreateBeeRequest request = new CreateBeeRequest(BeeType.CLAUDE_CODE, null, null);
            beeService.attachBee(groveId, cultivatorId, request);

            verify(keeper, timeout(500)).install(any(Bee.class), any(BeeSpec.class), any(CommandRunner.class));
        }
    }

    @Test
    void getBee_returnsBee() {
        BeeEntity entity = mock(BeeEntity.class);
        UUID beeId = UUID.randomUUID();
        when(beeRepository.findById(beeId)).thenReturn(Optional.of(entity));
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        when(entity.toModel()).thenReturn(bee);

        Optional<Bee> result = beeService.getBee(beeId);

        assertThat(result).isPresent().contains(bee);
    }

    @Test
    void getBee_notFound_returnsEmpty() {
        when(beeRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(beeService.getBee(UUID.randomUUID())).isEmpty();
    }

    @Test
    void listBees_returnsAllBeesForGrove() {
        BeeEntity entity1 = mock(BeeEntity.class);
        BeeEntity entity2 = mock(BeeEntity.class);
        Bee bee1 = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        Bee bee2 = Bee.hatching(groveId, BeeSpec.of(BeeType.GEMINI));
        when(beeRepository.findByGroveId(groveId)).thenReturn(List.of(entity1, entity2));
        when(entity1.toModel()).thenReturn(bee1);
        when(entity2.toModel()).thenReturn(bee2);

        List<Bee> result = beeService.listBees(groveId);

        assertThat(result).hasSize(2);
    }

    @Test
    void wake_notHibernating_noOp() {
        BeeEntity entity = mock(BeeEntity.class);
        UUID beeId = UUID.randomUUID();
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE));
        when(beeRepository.findById(beeId)).thenReturn(Optional.of(entity));
        when(entity.toModel()).thenReturn(bee);

        Optional<Bee> result = beeService.wake(beeId);

        assertThat(result).isPresent();
        assertThat(result.get().state()).isEqualTo(BeeState.HATCHING);
        verify(beeRepository, never()).save(any());
    }

    @Test
    void wake_hibernating_callsRelease() {
        BeeEntity entity = mock(BeeEntity.class);
        UUID beeId = UUID.randomUUID();
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.HIBERNATING);
        when(beeRepository.findById(beeId)).thenReturn(Optional.of(entity));
        when(entity.toModel()).thenReturn(bee);

        BeeKeeper keeper = setupRegisteredKeeper(BeeType.CLAUDE_CODE);
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(keeper.release(any(), any())).thenReturn(CompletableFuture.completedFuture(bee));

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    TransactionSynchronization sync = invocation.getArgument(0);
                    sync.afterCommit();
                    return null;
                });

            beeService.wake(beeId);

            verify(keeper, timeout(500)).release(eq(bee), any(CommandRunner.class));
        }
    }

    @Test
    void smoke_persistsSmokedState() {
        BeeEntity entity = mock(BeeEntity.class);
        UUID beeId = UUID.randomUUID();
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.BUZZING);
        when(beeRepository.findById(beeId)).thenReturn(Optional.of(entity));
        when(entity.toModel()).thenReturn(bee);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    TransactionSynchronization sync = invocation.getArgument(0);
                    sync.afterCommit();
                    return null;
                });

            Optional<Bee> result = beeService.smoke(beeId);

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(BeeState.SMOKED);
            verify(beeRepository).save(any(BeeEntity.class));
        }
    }

    private void setupFlourishingGrove() {
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(groveEntity.getState()).thenReturn(GroveState.FLOURISHING);
    }

    private BeeKeeper setupRegisteredKeeper(BeeType type) {
        BeeKeeper keeper = mock(BeeKeeper.class);
        when(beeKeeperRegistry.get(type)).thenReturn(Optional.of(keeper));
        return keeper;
    }
}
