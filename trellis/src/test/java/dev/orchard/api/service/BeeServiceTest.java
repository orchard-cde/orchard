package dev.orchard.api.service;

import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.event.BeeStateChangedEvent;
import dev.orchard.apiary.BeeKeeper;
import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.core.model.*;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.Vine;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
    @Mock private ProviderRegistry providerRegistry;

    private BeeService beeService;

    private final UUID groveId = UUID.randomUUID();
    private final UUID cultivatorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        beeService = new BeeService(beeKeeperRegistry, beeRepository, groveRepository,
            credentialResolver, eventPublisher, providerRegistry);
    }

    /** Stubs the registry -> default provider -> vine -> commands chain that {@code commandRunnerFor} walks. */
    private void stubCommandRunner() {
        GroveProvider provider = mock(GroveProvider.class);
        Vine vine = mock(Vine.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.vine(any())).thenReturn(vine);
        when(vine.commands()).thenReturn(mock(CommandRunner.class));
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
        stubCommandRunner();

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
        stubCommandRunner();
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
        stubCommandRunner();

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

    @Test
    void smoke_withRegisteredKeeper_callsSmokeCommand() {
        BeeEntity entity = mock(BeeEntity.class);
        UUID beeId = UUID.randomUUID();
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.BUZZING);
        when(beeRepository.findById(beeId)).thenReturn(Optional.of(entity));
        when(entity.toModel()).thenReturn(bee);

        BeeKeeper keeper = setupRegisteredKeeper(BeeType.CLAUDE_CODE);
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(keeper.smoke(any(), any())).thenReturn(CompletableFuture.completedFuture(bee));
        stubCommandRunner();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    TransactionSynchronization sync = invocation.getArgument(0);
                    sync.afterCommit();
                    return null;
                });

            beeService.smoke(beeId);

            verify(keeper, timeout(500)).smoke(eq(bee), any(CommandRunner.class));
        }
    }

    @Test
    void publishAfterCommit_withNoActiveTransaction_publishesImmediately() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);
            tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(false);

            beeService.publishAfterCommit(event);

            verify(eventPublisher).publishEvent(event);
            tsm.verify(() -> TransactionSynchronizationManager.registerSynchronization(any()), never());
        }
    }

    @Test
    void publishAfterCommit_withinATransaction_withholdsTheEventUntilCommit() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);
        List<TransactionSynchronization> registered = newSynchronizationSink();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            stubActiveTransaction(tsm, registered);

            beeService.publishAfterCommit(event);

            verify(eventPublisher, never()).publishEvent(any());
            assertThat(registered).hasSize(1);

            registered.get(0).afterCommit();

            verify(eventPublisher, times(1)).publishEvent(event);
        }
    }

    @Test
    void publishAfterCommit_publishesOnceWhenBothCallbacksRun() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);
        List<TransactionSynchronization> registered = newSynchronizationSink();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            stubActiveTransaction(tsm, registered);
            beeService.publishAfterCommit(event);

            registered.get(0).afterCommit();
            registered.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(eventPublisher, times(1)).publishEvent(event);
        }
    }

    @Test
    void publishAfterCommit_calledFromInsideAnotherAfterCommit_stillPublishesOnce() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);
        List<TransactionSynchronization> registered = newSynchronizationSink();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            stubActiveTransaction(tsm, registered);

            // An outer synchronization that calls the helper from its own afterCommit, which is
            // the shape the smoke path has.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    beeService.publishAfterCommit(event);
                }
            });
            assertThat(registered).hasSize(1);

            registered.get(0).afterCommit();

            // The helper registered a second synchronization from inside that callback. Spring
            // snapshots the list before invoking afterCommit, so this one only ever receives
            // afterCompletion -- fire exactly that, and nothing else.
            assertThat(registered).hasSize(2);
            registered.get(1).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(eventPublisher, times(1)).publishEvent(event);
        }
    }

    @Test
    void publishAfterCommit_onRollback_publishesNothing() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);
        List<TransactionSynchronization> registered = newSynchronizationSink();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            stubActiveTransaction(tsm, registered);
            beeService.publishAfterCommit(event);

            registered.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Test
    void publishAfterCommit_whenAListenerThrows_doesNotPropagateToTheCaller() {
        BeeStateChangedEvent event = BeeStateChangedEvent.of(
            UUID.randomUUID(), groveId, BeeState.HIBERNATING, BeeState.BUZZING);
        doThrow(new IllegalStateException("listener blew up"))
            .when(eventPublisher).publishEvent(any(Object.class));

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);
            tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(false);

            assertThatNoException().isThrownBy(() -> beeService.publishAfterCommit(event));
        }
    }

    @Test
    void wake_hibernating_publishesBuzzingEvent() {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE))
            .withState(BeeState.HIBERNATING);
        BeeEntity entity = mock(BeeEntity.class);
        when(beeRepository.findById(bee.id())).thenReturn(Optional.of(entity));
        when(entity.toModel()).thenReturn(bee);

        BeeKeeper keeper = setupRegisteredKeeper(BeeType.CLAUDE_CODE);
        GroveEntity groveEntity = mock(GroveEntity.class);
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(groveEntity));
        when(keeper.release(any(), any())).thenReturn(CompletableFuture.completedFuture(bee));
        stubCommandRunner();

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    TransactionSynchronization sync = invocation.getArgument(0);
                    sync.afterCommit();
                    return null;
                });

            beeService.wake(bee.id());

            ArgumentCaptor<BeeStateChangedEvent> published =
                ArgumentCaptor.forClass(BeeStateChangedEvent.class);
            verify(eventPublisher, timeout(500)).publishEvent(published.capture());
            assertThat(published.getValue().beeId()).isEqualTo(bee.id());
            assertThat(published.getValue().newState()).isEqualTo(BeeState.BUZZING);
        }
    }

    private List<TransactionSynchronization> newSynchronizationSink() {
        return new ArrayList<>();
    }

    private void stubActiveTransaction(MockedStatic<TransactionSynchronizationManager> tsm,
                                       List<TransactionSynchronization> sink) {
        tsm.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
        tsm.when(TransactionSynchronizationManager::isActualTransactionActive).thenReturn(true);
        tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
            .thenAnswer(invocation -> {
                sink.add(invocation.getArgument(0));
                return null;
            });
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
