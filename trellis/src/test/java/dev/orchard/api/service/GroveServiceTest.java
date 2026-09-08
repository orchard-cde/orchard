package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.*;
import dev.orchard.core.model.Seedling.SeedlingSpec;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.roots.entity.FruitEntity;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.FruitRepository;
import dev.orchard.roots.repository.GroveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroveServiceTest {

    @Mock private GroveRepository groveRepository;
    @Mock private FruitRepository fruitRepository;
    @Mock private ProviderRegistry providerRegistry;
    @Mock private CultivatorService cultivatorService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SshPublicKeyService sshPublicKeyService;

    private GroveService groveService;

    @BeforeEach
    void setUp() {
        groveService = new GroveService(
            groveRepository, fruitRepository, providerRegistry,
            new DevcontainerCliConfig("0.87.0", 0, 0),
            cultivatorService, eventPublisher, sshPublicKeyService
        );
    }

    @Test
    void plantGrove_createsGroveInPlantingState() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/my-repo", "main", null, null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.state()).isEqualTo(GroveState.PLANTING);
            assertThat(result.cultivatorId()).isEqualTo(cultivatorId);
            assertThat(result.repositoryUrl()).isEqualTo("https://github.com/user/my-repo");
            assertThat(result.branch()).isEqualTo("main");
        }
    }

    @Test
    void plantGrove_autoGeneratesNameFromRepoUrl() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/my-repo.git", "develop", null, null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.name()).isEqualTo("my-repo-develop");
        }
    }

    @Test
    void plantGrove_usesProvidedName() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", "custom-name", null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.name()).isEqualTo("custom-name");
        }
    }

    @Test
    void plantGrove_defaultsToSmallMachineSize() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.seedling()).isNotNull();
            assertThat(result.seedling().spec()).isEqualTo(SeedlingSpec.small());
        }
    }

    @Test
    void plantGrove_respectsMediumMachineSize() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "medium", null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.seedling().spec()).isEqualTo(SeedlingSpec.medium());
        }
    }

    @Test
    void plantGrove_respectsLargeMachineSize() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "large", null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.seedling().spec()).isEqualTo(SeedlingSpec.large());
        }
    }

    @Test
    void plantGrove_callsEnsureCultivator() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            groveService.plantGrove(cultivatorId, request);

            verify(cultivatorService).ensureCultivator(cultivatorId);
        }
    }

    @Test
    void plantGrove_savesGroveEntity() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            groveService.plantGrove(cultivatorId, request);

            ArgumentCaptor<GroveEntity> captor = ArgumentCaptor.forClass(GroveEntity.class);
            verify(groveRepository).save(captor.capture());
            assertThat(captor.getValue().getState()).isEqualTo(GroveState.PLANTING);
            assertThat(captor.getValue().getCultivatorId()).isEqualTo(cultivatorId);
        }
    }

    @Test
    void plantGrove_createsSeedlingInGerminatingState() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.seedling()).isNotNull();
            assertThat(result.seedling().state()).isEqualTo(SeedlingState.GERMINATING);
            assertThat(result.seedling().groveId()).isEqualTo(result.id());
        }
    }

    @Test
    void getGrove_returnsGroveWhenFound() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GroveEntity entity = GroveEntity.fromModel(
            Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
                .withState(GroveState.PLANTING)
        );
        when(groveRepository.findById(groveId)).thenReturn(Optional.of(entity));
        when(fruitRepository.findByGroveId(any())).thenReturn(List.of());

        Optional<Grove> result = groveService.getGrove(groveId);

        assertThat(result).isPresent();
    }

    @Test
    void getGrove_returnsEmptyWhenNotFound() {
        when(groveRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(groveService.getGrove(UUID.randomUUID())).isEmpty();
    }

    @Test
    void getGrovesForCultivator_excludesClearedByDefault() {
        UUID cultivatorId = UUID.randomUUID();
        GroveEntity entity = GroveEntity.fromModel(
            Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
        );
        when(groveRepository.findByCultivatorIdAndStateNotIn(cultivatorId, List.of(GroveState.CLEARED)))
            .thenReturn(List.of(entity));
        when(fruitRepository.findByGroveId(any())).thenReturn(List.of());

        List<Grove> result = groveService.getGrovesForCultivator(cultivatorId);

        assertThat(result).hasSize(1);
        verify(groveRepository).findByCultivatorIdAndStateNotIn(cultivatorId, List.of(GroveState.CLEARED));
        verify(groveRepository, never()).findByCultivatorId(cultivatorId);
    }

    @Test
    void getGrovesForCultivator_includesClearedWhenRequested() {
        UUID cultivatorId = UUID.randomUUID();
        GroveEntity activeEntity = GroveEntity.fromModel(
            Grove.plant(cultivatorId, "active", "https://github.com/user/repo", "main")
        );
        GroveEntity clearedEntity = GroveEntity.fromModel(
            Grove.plant(cultivatorId, "cleared", "https://github.com/user/repo", "main")
                .withState(GroveState.CLEARED)
        );
        when(groveRepository.findByCultivatorId(cultivatorId))
            .thenReturn(List.of(activeEntity, clearedEntity));
        when(fruitRepository.findByGroveId(any())).thenReturn(List.of());

        List<Grove> result = groveService.getGrovesForCultivator(cultivatorId, true);

        assertThat(result).hasSize(2);
        verify(groveRepository).findByCultivatorId(cultivatorId);
        verify(groveRepository, never()).findByCultivatorIdAndStateNotIn(any(), any());
    }

    @Test
    void getGrovesForCultivator_returnsEmptyListWhenNone() {
        UUID cultivatorId = UUID.randomUUID();
        when(groveRepository.findByCultivatorIdAndStateNotIn(cultivatorId, List.of(GroveState.CLEARED)))
            .thenReturn(List.of());

        List<Grove> result = groveService.getGrovesForCultivator(cultivatorId);

        assertThat(result).isEmpty();
    }

    @Test
    void updateGroveState_savesEntity() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main");
        when(groveRepository.findById(grove.id())).thenReturn(Optional.empty());

        groveService.updateGroveState(grove);

        verify(groveRepository).save(any(GroveEntity.class));
    }

    @Test
    void updateGroveState_publishesEventOnStateChange() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main")
            .withState(GroveState.PLANTING);

        GroveEntity existingEntity = GroveEntity.fromModel(grove.withState(GroveState.PREPARING));
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(existingEntity));

        groveService.updateGroveState(grove);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(GroveStateChangedEvent.class);
        GroveStateChangedEvent event = (GroveStateChangedEvent) eventCaptor.getValue();
        assertThat(event.previousState()).isEqualTo(GroveState.PREPARING);
        assertThat(event.newState()).isEqualTo(GroveState.PLANTING);
    }

    @Test
    void updateGroveState_doesNotPublishEventWhenStateUnchanged() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main")
            .withState(GroveState.PLANTING);

        GroveEntity existingEntity = GroveEntity.fromModel(grove);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(existingEntity));

        groveService.updateGroveState(grove);

        verify(eventPublisher, never()).publishEvent(any());
    }

    // --- cloud-init status classification (issue #113) --------------------------------
    // The previous waitForCloudInit used `status.contains("done") || contains("not available")`,
    // which mistook a *failed* or unreadable cloud-init for completion and then surfaced the
    // missing devcontainer CLI downstream as a confusing `devcontainer: command not found`.

    @Test
    void classifyCloudInitStatus_treatsErrorAsFailed() {
        assertThat(GroveService.classifyCloudInitStatus("status: error"))
            .isEqualTo(GroveService.CloudInitStatus.FAILED);
    }

    @Test
    void classifyCloudInitStatus_treatsDoneAsDone() {
        assertThat(GroveService.classifyCloudInitStatus("status: done"))
            .isEqualTo(GroveService.CloudInitStatus.DONE);
    }

    @Test
    void classifyCloudInitStatus_treatsDegradedDoneAsDone() {
        // Terminal-but-degraded: cloud-init finished; let verifyDevcontainerCli be the authority.
        assertThat(GroveService.classifyCloudInitStatus("status: degraded done"))
            .isEqualTo(GroveService.CloudInitStatus.DONE);
    }

    @Test
    void classifyCloudInitStatus_treatsRunningAsInProgress() {
        assertThat(GroveService.classifyCloudInitStatus("status: running"))
            .isEqualTo(GroveService.CloudInitStatus.IN_PROGRESS);
    }

    @Test
    void classifyCloudInitStatus_treatsNotRunAsInProgress() {
        assertThat(GroveService.classifyCloudInitStatus("status: not run"))
            .isEqualTo(GroveService.CloudInitStatus.IN_PROGRESS);
    }

    @Test
    void classifyCloudInitStatus_treatsEmptyOrUnreadableSnapshotAsInProgress() {
        // `cloud-init status` not yet answerable early in boot — must NOT be read as "done".
        assertThat(GroveService.classifyCloudInitStatus(""))
            .isEqualTo(GroveService.CloudInitStatus.IN_PROGRESS);
    }

    // --- provisionGrove: registered SSH keys baked into the seedling --------------------

    @Test
    void provisionGrove_attachesRegisteredKeysToSeedlingBeforePlanting() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withSeedling(Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small()));

        SshPublicKey registered = SshPublicKey.register(cultivatorId, "laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");
        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of(registered));

        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        ArgumentCaptor<Seedling> seedlingCaptor = ArgumentCaptor.forClass(Seedling.class);
        when(provider.plantSubstrate(seedlingCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                grove.seedling().withState(SeedlingState.BLIGHTED)));

        groveService.provisionGrove(grove, SeedSpec.AUTO);

        assertThat(seedlingCaptor.getValue().authorizedKeys())
            .containsExactly(registered.publicKey());
    }

    @Test
    void provisionGrove_withNoRegisteredKeys_plantsSeedlingWithoutKeys() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withSeedling(Seedling.germinate(UUID.randomUUID(), SeedlingSpec.small()));

        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of());

        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        ArgumentCaptor<Seedling> seedlingCaptor = ArgumentCaptor.forClass(Seedling.class);
        when(provider.plantSubstrate(seedlingCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                grove.seedling().withState(SeedlingState.BLIGHTED)));

        groveService.provisionGrove(grove, SeedSpec.AUTO);

        assertThat(seedlingCaptor.getValue().authorizedKeys()).isEmpty();
    }

    // --- stopGrove / startGrove ---

    @Test
    void stopGrove_growingGrove_transitionsToDormant() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
                .withState(GroveState.GROWING)
                .withSeedling(new Seedling(UUID.randomUUID(), null, null, "127.0.0.1", 22,
                    SeedlingState.SAPLING, SeedlingSpec.small(), null, null));
            GroveEntity entity = GroveEntity.fromModel(grove);
            when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
            when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

            Optional<Grove> result = groveService.stopGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.DORMANT);
            ArgumentCaptor<GroveEntity> captor = ArgumentCaptor.forClass(GroveEntity.class);
            verify(groveRepository).save(captor.capture());
            assertThat(captor.getValue().getState()).isEqualTo(GroveState.DORMANT);
        }
    }

    @Test
    void stopGrove_flourishingGrove_transitionsToDormant() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
                .withState(GroveState.FLOURISHING)
                .withSeedling(new Seedling(UUID.randomUUID(), null, null, "127.0.0.1", 22,
                    SeedlingState.SAPLING, SeedlingSpec.small(), null, null));
            GroveEntity entity = GroveEntity.fromModel(grove);
            when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
            when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

            Optional<Grove> result = groveService.stopGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.DORMANT);
        }
    }

    @Test
    void stopGrove_plantingGrove_returnsUnchanged() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withState(GroveState.PLANTING);
        GroveEntity entity = GroveEntity.fromModel(grove);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
        when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

        Optional<Grove> result = groveService.stopGrove(grove.id());

        assertThat(result).isPresent();
        assertThat(result.get().state()).isEqualTo(GroveState.PLANTING);
        verify(groveRepository, never()).save(any());
    }

    @Test
    void stopGrove_nonexistentGrove_returnsEmpty() {
        when(groveRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(groveService.stopGrove(UUID.randomUUID())).isEmpty();
    }

    @Test
    void startGrove_dormantGrove_transitionsToPlanting() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
                .withState(GroveState.DORMANT)
                .withSeedling(new Seedling(UUID.randomUUID(), null, null, null, 22,
                    SeedlingState.WITHERED, SeedlingSpec.small(), null, null));
            GroveEntity entity = GroveEntity.fromModel(grove);
            when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
            when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

            Optional<Grove> result = groveService.startGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.PLANTING);
            assertThat(result.get().seedling()).isNotNull();
            assertThat(result.get().seedling().state()).isEqualTo(SeedlingState.GERMINATING);
        }
    }

    @Test
    void startGrove_growingGrove_returnsUnchanged() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withState(GroveState.GROWING);
        GroveEntity entity = GroveEntity.fromModel(grove);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
        when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

        Optional<Grove> result = groveService.startGrove(grove.id());

        assertThat(result).isPresent();
        assertThat(result.get().state()).isEqualTo(GroveState.GROWING);
        verify(groveRepository, never()).save(any());
    }

    @Test
    void startGrove_nonexistentGrove_returnsEmpty() {
        when(groveRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(groveService.startGrove(UUID.randomUUID())).isEmpty();
    }

    // --- tearDownAndRecord (issue #228) ------------------------------------------------

    /**
     * A grove whose seedling has NO ip address, so the socket probe in
     * compostFruitsIfReachable is skipped entirely and the test does no network I/O.
     * `Grove.plant` assigns the id; `Seedling.germinate` leaves ipAddress null.
     */
    private Grove groveWithSeedling() {
        Grove grove = Grove.plant(
            UUID.randomUUID(), "g", "https://example.invalid/r.git", "main");
        Seedling seedling = Seedling
            .germinate(grove.id(), new SeedlingSpec(2, 4096, 20, "small", null));
        return grove.withSeedling(seedling);
    }

    private GroveEntity entityFor(Grove grove, GroveState state) {
        return GroveEntity.fromModel(grove.withState(state));
    }

    @Test
    void tearDownAndRecord_marksOrphanedWhenUprootFails() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        // Prove the failure came from uproot, not from something incidental upstream.
        verify(provider).uproot(any());
        ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
        verify(groveRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(GroveState.ORPHANED);
    }

    /**
     * The defect this plan exists to fix. The old code set CLEARED inside a `finally`, so the
     * failure path and the success path produced the same terminal state. Asserting "not CLEARED"
     * is the named assertion from spec A1 — asserting "ORPHANED" alone would still pass if the
     * method were changed to set some other non-terminal state, so both are checked.
     */
    @Test
    void tearDownAndRecord_neverMarksClearedOnFailure() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        verify(provider).uproot(any());
        ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
        verify(groveRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
            .extracting(GroveEntity::getState)
            .doesNotContain(GroveState.CLEARED);
    }

    /**
     * Retaining the fruit rows is the point: they name what leaked. The old ordering deleted them
     * before uproot ran, so a teardown failure destroyed the only record of the orphaned resource.
     */
    @Test
    void tearDownAndRecord_retainsFruitRowsWhenTeardownFails() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        verify(provider).uproot(any());
        verify(fruitRepository, never()).deleteAll(any());
    }

    @Test
    void tearDownAndRecord_marksSuccessStateAndDeletesFruitWhenTeardownSucceeds() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
        verify(groveRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(GroveState.CLEARED);
        verify(fruitRepository).deleteAll(any());
    }

    /**
     * The two phases must not be conflated. If bookkeeping fails AFTER the substrate was released,
     * the grove must not be reported ORPHANED — nothing leaked, and ORPHANED would send an operator
     * hunting for a resource that no longer exists. Guards the Phase 1 / Phase 2 split.
     */
    @Test
    void tearDownAndRecord_doesNotReportOrphanedWhenOnlyBookkeepingFails() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(groveRepository.save(any())).thenThrow(new RuntimeException("db down"));

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        verify(provider).uproot(any());
        assertThat(entity.getState()).isNotEqualTo(GroveState.ORPHANED);
    }

    /**
     * Ordering guard. Verifies teardown precedes record deletion, which is the clause that makes a
     * failed teardown recoverable. Falsify this deliberately in Step 5.
     */
    @Test
    void tearDownAndRecord_uprootsBeforeDeletingFruitRows() {
        Grove grove = groveWithSeedling();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.getDefault()).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(fruitRepository.findByGroveId(grove.id())).thenReturn(List.of());

        groveService.tearDownAndRecord(grove.id(), grove, entity, GroveState.CLEARED);

        InOrder order = inOrder(provider, fruitRepository);
        order.verify(provider).uproot(any());
        order.verify(fruitRepository).deleteAll(any());
    }
}
