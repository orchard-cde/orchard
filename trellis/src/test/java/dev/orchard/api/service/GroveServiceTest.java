package dev.orchard.api.service;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.event.GroveStateChangedEvent;
import dev.orchard.core.model.*;
import dev.orchard.harvest.PlotSerializer;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.Vine;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private GroveProvider defaultProvider;
    @Mock private GroveProvider ownerProvider;
    @Mock private BeeService beeService;
    @Mock private PlatformTransactionManager transactionManager;

    private GroveService groveService;

    @BeforeEach
    void setUp() {
        groveService = new GroveService(
            groveRepository, fruitRepository, providerRegistry,
            new DevcontainerCliConfig("0.87.0", 0, 0),
            cultivatorService, eventPublisher, sshPublicKeyService,
            beeService, new TransactionTemplate(transactionManager)
        );
        lenient().when(providerRegistry.getDefault()).thenReturn(defaultProvider);
        lenient().when(providerRegistry.require("test-default")).thenReturn(defaultProvider);
        lenient().when(providerRegistry.require("owner")).thenReturn(ownerProvider);
        lenient().when(defaultProvider.getProviderId()).thenReturn("test-default");
        lenient().when(defaultProvider.sizes()).thenReturn(PlotSizes.DEFAULT);
        lenient().when(defaultProvider.reserve(any(), any())).thenAnswer(inv ->
            VmPlot.germinate(inv.getArgument(0), "test-default", inv.getArgument(1)));
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

            assertThat(result.plot()).isNotNull();
            assertThat(((VmPlot) result.plot()).spec()).isEqualTo(VmSpec.small());
        }
    }

    @Test
    void plantGrove_respectsMediumMachineSize() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "medium", null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(((VmPlot) result.plot()).spec()).isEqualTo(VmSpec.medium());
        }
    }

    @Test
    void plantGrove_respectsLargeMachineSize() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "large", null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(((VmPlot) result.plot()).spec()).isEqualTo(VmSpec.large());
        }
    }

    @Test
    void plantGrove_takesTierResourcesFromTheDefaultProvidersCatalogue() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            when(defaultProvider.sizes()).thenReturn(new PlotSizes() {
                @Override
                public PlotResources medium() {
                    return new PlotResources(6, 6000, 60);
                }
            });
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "medium", "ttyS1");

            groveService.plantGrove(UUID.randomUUID(), request);

            ArgumentCaptor<SeedlingSpec> captor = ArgumentCaptor.forClass(SeedlingSpec.class);
            verify(defaultProvider).reserve(any(), captor.capture());
            assertThat(captor.getValue()).isEqualTo(new SeedlingSpec("medium", 6, 6000, 60, "ttyS1"));
        }
    }

    @Test
    void plantGrove_unknownMachineSize_fallsBackToSmall() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, "unknown", "ttyS1");

            groveService.plantGrove(UUID.randomUUID(), request);

            ArgumentCaptor<SeedlingSpec> captor = ArgumentCaptor.forClass(SeedlingSpec.class);
            verify(defaultProvider).reserve(any(), captor.capture());
            assertThat(captor.getValue()).isEqualTo(new SeedlingSpec("small", 2, 4096, 20, "ttyS1"));
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
    void plantGrove_createsPlotInGerminatingState() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            UUID cultivatorId = UUID.randomUUID();
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            Grove result = groveService.plantGrove(cultivatorId, request);

            assertThat(result.plot()).isNotNull();
            assertThat(result.plot().state()).isEqualTo(PlotState.GERMINATING);
            assertThat(result.plot().groveId()).isEqualTo(result.id());
        }
    }

    @Test
    void getGrove_returnsGroveWhenFound() {
        UUID groveId = UUID.randomUUID();
        UUID cultivatorId = UUID.randomUUID();
        GroveEntity entity = GroveEntity.fromModel(
            withGerminatingPlot(Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main"))
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
            withGerminatingPlot(Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main"))
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
            withGerminatingPlot(Grove.plant(cultivatorId, "active", "https://github.com/user/repo", "main"))
        );
        GroveEntity clearedEntity = GroveEntity.fromModel(
            withGerminatingPlot(Grove.plant(cultivatorId, "cleared", "https://github.com/user/repo", "main"))
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

    // cloud-init status classification (issue #113)
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

    // provisionGrove: registered SSH keys baked into the VM plot

    @Test
    void plantGrove_reservesOnDefaultProvider() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            var request = new CreateGroveRequest("https://github.com/user/repo", "main", null, null, null);

            Grove result = groveService.plantGrove(UUID.randomUUID(), request);

            verify(defaultProvider).reserve(any(), any());
            assertThat(result.plot().providerId()).isEqualTo("test-default");
            ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
            verify(groveRepository).save(saved.capture());
            assertThat(saved.getValue().plot().providerId()).isEqualTo("test-default");
        }
    }

    @Test
    void provisionGrove_dispatchesToPlotOwner() throws Exception {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main")
            .withPlot(VmPlot.germinate(UUID.randomUUID(), "owner", SeedlingSpec.from(VmSpec.small())));
        when(sshPublicKeyService.listForCultivator(any())).thenReturn(List.of());
        Vine vine = mock(Vine.class);
        CommandRunner runner = mock(CommandRunner.class);
        when(ownerProvider.vine(any())).thenReturn(vine);
        when(vine.commands()).thenReturn(runner);
        when(ownerProvider.plantSubstrate(any())).thenAnswer(inv ->
            CompletableFuture.completedFuture(((Plot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        when(ownerProvider.growFruit(any(), any())).thenAnswer(inv ->
            CompletableFuture.completedFuture(((Fruit) inv.getArgument(1)).withState(FruitState.RIPE)));
        when(runner.execute(any())).thenReturn("status: done", "abc123", "yes");

        groveService.provisionGrove(grove, SeedSpec.AUTO);

        verify(ownerProvider).plantSubstrate(any());
        verify(ownerProvider, atLeastOnce()).vine(any());
        verify(ownerProvider).growFruit(any(), any());
        verifyNoInteractions(defaultProvider);
    }

    @Test
    void provisionGrove_attachesRegisteredKeysToPlotBeforePlanting() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withPlot(VmPlot.germinate(UUID.randomUUID(), "p", SeedlingSpec.from(VmSpec.small())));

        SshPublicKey registered = SshPublicKey.register(cultivatorId, "laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");
        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of(registered));

        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("p")).thenReturn(provider);
        ArgumentCaptor<Plot> plotCaptor = ArgumentCaptor.forClass(Plot.class);
        when(provider.plantSubstrate(plotCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                grove.plot().withState(PlotState.BLIGHTED)));

        groveService.provisionGrove(grove, SeedSpec.AUTO);

        assertThat(((VmPlot) plotCaptor.getValue()).authorizedKeys())
            .containsExactly(registered.publicKey());
    }

    @Test
    void provisionGrove_withNoRegisteredKeys_plantsPlotWithoutKeys() {
        UUID cultivatorId = UUID.randomUUID();
        Grove grove = Grove.plant(cultivatorId, "test", "https://github.com/user/repo", "main")
            .withPlot(VmPlot.germinate(UUID.randomUUID(), "p", SeedlingSpec.from(VmSpec.small())));

        when(sshPublicKeyService.listForCultivator(cultivatorId)).thenReturn(List.of());

        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("p")).thenReturn(provider);
        ArgumentCaptor<Plot> plotCaptor = ArgumentCaptor.forClass(Plot.class);
        when(provider.plantSubstrate(plotCaptor.capture()))
            .thenReturn(CompletableFuture.completedFuture(
                grove.plot().withState(PlotState.BLIGHTED)));

        groveService.provisionGrove(grove, SeedSpec.AUTO);

        assertThat(((VmPlot) plotCaptor.getValue()).authorizedKeys()).isEmpty();
    }

    @Test
    void plantGrove_noDefaultProvider_throwsAndSavesNothing() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            when(providerRegistry.getDefault())
                .thenThrow(new IllegalStateException("No default grove provider configured"));
            var request = new CreateGroveRequest("https://github.com/user/my-repo", "main", null, null, null);

            assertThatThrownBy(() -> groveService.plantGrove(UUID.randomUUID(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No default grove provider configured");
            verify(groveRepository, never()).save(any());
        }
    }

    private static final PlotSerializer PLOTS = new PlotSerializer();
    private static final Seed SEED = Seed.devcontainer().name("test").image("ubuntu").build();

    private record Transition(GroveState expected, GroveState next, Plot plot) {}

    private List<Transition> recordTransitions(int rows) {
        List<Transition> transitions = new java.util.concurrent.CopyOnWriteArrayList<>();
        lenient().when(groveRepository.transitionIfState(any(), any(), any(), any())).thenAnswer(inv -> {
            String plotJson = inv.getArgument(3);
            transitions.add(new Transition(inv.getArgument(1), inv.getArgument(2),
                plotJson == null ? null : PLOTS.deserialize(plotJson)));
            return rows;
        });
        return transitions;
    }

    private static VmPlot ownerPlot(PlotState state, String host) {
        return new VmPlot(UUID.randomUUID(), null, "owner", "vm-1", state,
            Instant.now(), null, host, 22, List.of(), VmSpec.small());
    }

    private Grove ownerGrove(GroveState state, PlotState plotState, FruitState fruitState) {
        Grove grove = Grove.plant(UUID.randomUUID(), "g", "https://example.invalid/r.git", "main")
            .withState(state)
            .withPlot(ownerPlot(plotState, "127.0.0.1"));
        return grove.withFruit(Fruit.bud(grove.id(), grove.plot().id(), SEED)
            .withContainerDetails("c1", List.of())
            .withState(fruitState));
    }

    /** Stubs the rows {@code entityToModel} reads, so the grove round-trips through the service. */
    private GroveEntity stubStored(Grove grove) {
        GroveEntity entity = GroveEntity.fromModel(grove);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));
        lenient().when(fruitRepository.findByGroveId(grove.id()))
            .thenReturn(grove.fruits().stream().map(FruitEntity::fromModel).toList());
        return entity;
    }

    private static Grove storedAs(Grove grove, Transition last) {
        return grove.withState(last.next()).withPlot(last.plot());
    }

    private List<GroveState> recordSavedStates() {
        List<GroveState> atSave = new java.util.concurrent.CopyOnWriteArrayList<>();
        lenient().when(groveRepository.save(any())).thenAnswer(inv -> {
            atSave.add(((GroveEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });
        return atSave;
    }

    @SuppressWarnings("unchecked")
    private List<Fruit> lastSavedFruits() {
        ArgumentCaptor<List<FruitEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(fruitRepository, atLeastOnce()).saveAll(saved.capture());
        return saved.getValue().stream().map(FruitEntity::toModel).toList();
    }

    @Test
    void startGrove_unregisteredOwner_throwsIllegalState() {
        Grove grove = Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main")
            .withState(GroveState.DORMANT)
            .withPlot(new VmPlot(UUID.randomUUID(), null, "gone", null, PlotState.DORMANT,
                null, null, null, 22, List.of(), VmSpec.small()));
        stubStored(grove);
        when(providerRegistry.require("gone"))
            .thenThrow(new IllegalStateException("Provider 'gone' is not registered"));

        assertThatThrownBy(() -> groveService.startGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Provider 'gone' is not registered");
        verify(groveRepository, never()).save(any());
    }

    /** Groves stopped before stop preserved the plot have a WITHERED plot; nothing is re-planted. */
    @Test
    void startGrove_witheredPlot_refusedWithoutReserving() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.WITHERED, FruitState.PICKED);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.startGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WITHERED");
        verify(ownerProvider, never()).reserve(any(), any());
        verify(groveRepository, never()).save(any());
    }

    @Test
    void stopGrove_growingGrove_transitionsToDormant() {
        Grove grove = ownerGrove(GroveState.GROWING, PlotState.ROOTED, FruitState.RIPENING);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            Optional<Grove> result = groveService.stopGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.DORMANT);
            assertThat(atSave).containsExactly(GroveState.DORMANT);
        }
    }

    @Test
    void stopGrove_flourishingGrove_transitionsToDormant() {
        Grove grove = ownerGrove(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            Optional<Grove> result = groveService.stopGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.DORMANT);
            assertThat(atSave).containsExactly(GroveState.DORMANT);
        }
    }

    @Test
    void stopGrove_plantingGrove_throws409() {
        Grove grove = withGerminatingPlot(Grove.plant(UUID.randomUUID(), "test", "https://github.com/user/repo", "main"))
            .withState(GroveState.PLANTING);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.stopGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PLANTING")
            .hasMessageContaining("GERMINATING");
        verify(groveRepository, never()).save(any());
    }

    @Test
    void stopGrove_nonexistentGrove_returnsEmpty() {
        when(groveRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(groveService.stopGrove(UUID.randomUUID())).isEmpty();
    }

    @Test
    void stopGrove_neverUproots_keepsFruitRows_persistsDormantPlot() {
        Grove grove = ownerGrove(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.pickFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.PICKED)));
        when(ownerProvider.dormant(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Plot) inv.getArgument(0)).withState(PlotState.DORMANT)));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.stopGrove(grove.id()).orElseThrow();
        }
        groveService.completeStop(accepted, ownerProvider);

        InOrder order = inOrder(ownerProvider);
        order.verify(ownerProvider).pickFruit(any(), any());
        order.verify(ownerProvider).dormant(any());
        verify(ownerProvider, never()).uproot(any());
        verify(ownerProvider, never()).compostFruit(any(), any());
        verify(fruitRepository, never()).deleteAllById(any());
        verify(fruitRepository, never()).deleteAll(any());
        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.DORMANT);
        assertThat(last.next()).isEqualTo(GroveState.DORMANT);
        assertThat(last.plot().state()).isEqualTo(PlotState.DORMANT);
        assertThat(last.plot().id()).isEqualTo(grove.plot().id());
        assertThat(lastSavedFruits()).singleElement().satisfies(fruit -> {
            assertThat(fruit.id()).isEqualTo(grove.primaryFruit().id());
            assertThat(fruit.state()).isEqualTo(FruitState.PICKED);
        });
    }

    @Test
    void stopGrove_hibernatesBees() {
        Grove grove = ownerGrove(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            groveService.stopGrove(grove.id());

            InOrder order = inOrder(groveRepository, beeService);
            order.verify(groveRepository).save(any());
            order.verify(beeService).hibernateAll(grove.id());
            tsm.verify(() -> TransactionSynchronizationManager.registerSynchronization(any()));
            verifyNoInteractions(ownerProvider);
        }
    }

    @Test
    void stopGrove_fromBlightedWithRootedPlot_accepted() {
        Grove grove = ownerGrove(GroveState.BLIGHTED, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            assertThat(groveService.stopGrove(grove.id())).get()
                .extracting(Grove::state).isEqualTo(GroveState.DORMANT);
            assertThat(atSave).containsExactly(GroveState.DORMANT);
        }
    }

    @Test
    void stopGrove_fromBlightedWithDormantPlot_throws409() {
        Grove grove = ownerGrove(GroveState.BLIGHTED, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.stopGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BLIGHTED")
            .hasMessageContaining("DORMANT");
        verify(groveRepository, never()).save(any());
        verifyNoInteractions(beeService);
    }

    @Test
    void stopGrove_fromCleared_throws409() {
        Grove grove = ownerGrove(GroveState.CLEARED, PlotState.WITHERED, FruitState.PICKED);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.stopGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CLEARED");
        verify(groveRepository, never()).save(any());
        verifyNoInteractions(beeService);
    }

    @Test
    void stopGrove_dormantFails_blightsWithRootedPlot() {
        Grove grove = ownerGrove(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.pickFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.PICKED)));
        when(ownerProvider.dormant(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("poweroff timed out")));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.stopGrove(grove.id()).orElseThrow();
        }
        groveService.completeStop(accepted, ownerProvider);

        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.DORMANT);
        assertThat(last.next()).isEqualTo(GroveState.BLIGHTED);
        assertThat(last.plot().state()).isEqualTo(PlotState.ROOTED);
        verify(ownerProvider, never()).uproot(any());

        stubStored(storedAs(accepted, last));
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            assertThat(groveService.stopGrove(grove.id())).get()
                .extracting(Grove::state).isEqualTo(GroveState.DORMANT);
        }
    }

    @Test
    void startGrove_dormantGrove_transitionsToPlantingKeepingPlot() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();

        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            Optional<Grove> result = groveService.startGrove(grove.id());

            assertThat(result).isPresent();
            assertThat(result.get().state()).isEqualTo(GroveState.PLANTING);
            assertThat(result.get().plot().id()).isEqualTo(grove.plot().id());
            assertThat(result.get().plot().state()).isEqualTo(PlotState.DORMANT);
            assertThat(atSave).containsExactly(GroveState.PLANTING);
            verify(ownerProvider, never()).reserve(any(), any());
        }
    }

    @Test
    void startGrove_growingGrove_throws409() {
        Grove grove = ownerGrove(GroveState.GROWING, PlotState.ROOTED, FruitState.RIPENING);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.startGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GROWING");
        verify(groveRepository, never()).save(any());
    }

    @Test
    void startGrove_nonexistentGrove_returnsEmpty() {
        when(groveRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(groveService.startGrove(UUID.randomUUID())).isEmpty();
    }

    @Test
    void startGrove_neverPlants_watersThenRipens() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        when(ownerProvider.ripenFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.RIPE)));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.startGrove(grove.id()).orElseThrow();
        }
        groveService.completeStart(accepted, ownerProvider);

        InOrder order = inOrder(ownerProvider);
        order.verify(ownerProvider).water(any());
        order.verify(ownerProvider).ripenFruit(
            org.mockito.ArgumentMatchers.argThat(p -> p.state() == PlotState.ROOTED), any());
        verify(ownerProvider, never()).plantSubstrate(any());
        verify(ownerProvider, never()).reserve(any(), any());
        verify(ownerProvider, never()).growFruit(any(), any());
        verify(ownerProvider, never()).vine(any());
        verify(ownerProvider, never()).verifyDevcontainerCli(any(), any());
        assertThat(atSave).containsExactly(GroveState.PLANTING);
        assertThat(transitions).extracting(Transition::expected, Transition::next).containsExactly(
            org.assertj.core.groups.Tuple.tuple(GroveState.PLANTING, GroveState.GROWING),
            org.assertj.core.groups.Tuple.tuple(GroveState.GROWING, GroveState.FLOURISHING));
        assertThat(transitions.getLast().plot().state()).isEqualTo(PlotState.ROOTED);
        assertThat(lastSavedFruits()).singleElement()
            .extracting(Fruit::state).isEqualTo(FruitState.RIPE);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).map(e -> ((GroveStateChangedEvent) e).newState())
            .containsExactly(GroveState.PLANTING, GroveState.GROWING, GroveState.FLOURISHING);
    }

    @Test
    void startGrove_fromBlightedWithDormantPlot_walksPlantingGrowing() {
        Grove grove = ownerGrove(GroveState.BLIGHTED, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);
        List<GroveState> atSave = recordSavedStates();
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        when(ownerProvider.ripenFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.RIPE)));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.startGrove(grove.id()).orElseThrow();
        }
        groveService.completeStart(accepted, ownerProvider);

        assertThat(atSave).containsExactly(GroveState.PLANTING);
        assertThat(transitions).extracting(Transition::next)
            .containsExactly(GroveState.GROWING, GroveState.FLOURISHING);
        verify(ownerProvider, never()).plantSubstrate(any());
    }

    @Test
    void startGrove_refusedWhilePlotNotDormant() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.ROOTED, FruitState.RIPE);
        stubStored(grove);

        assertThatThrownBy(() -> groveService.startGrove(grove.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DORMANT")
            .hasMessageContaining("ROOTED");
        verify(groveRepository, never()).save(any());
        verifyNoInteractions(ownerProvider);
    }

    @Test
    void startGrove_ripenFailure_blightsWithRootedPlot_thenStopAccepted() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        when(ownerProvider.ripenFruit(any(), any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("container missing")));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.startGrove(grove.id()).orElseThrow();
        }
        groveService.completeStart(accepted, ownerProvider);

        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.GROWING);
        assertThat(last.next()).isEqualTo(GroveState.BLIGHTED);
        assertThat(last.plot().state()).isEqualTo(PlotState.ROOTED);

        stubStored(storedAs(accepted, last));
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            assertThat(groveService.stopGrove(grove.id())).get()
                .extracting(Grove::state).isEqualTo(GroveState.DORMANT);
        }
    }

    @Test
    void startGrove_waterFailure_blightsWithDormantPlot_thenStartAccepted() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        stubStored(grove);
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("boot timed out")));

        Grove accepted;
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            accepted = groveService.startGrove(grove.id()).orElseThrow();
        }
        groveService.completeStart(accepted, ownerProvider);

        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.PLANTING);
        assertThat(last.next()).isEqualTo(GroveState.BLIGHTED);
        assertThat(last.plot().state()).isEqualTo(PlotState.DORMANT);
        verify(ownerProvider, never()).ripenFruit(any(), any());

        stubStored(storedAs(accepted, last));
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            assertThat(groveService.startGrove(grove.id())).get()
                .extracting(Grove::state).isEqualTo(GroveState.PLANTING);
        }
    }

    @Test
    void startGrove_noFruit_blightsWithRootedPlot() {
        Grove grove = ownerGrove(GroveState.PLANTING, PlotState.DORMANT, FruitState.PICKED).withFruits(List.of());
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));

        groveService.completeStart(grove, ownerProvider);

        assertThat(transitions).extracting(Transition::next)
            .containsExactly(GroveState.GROWING, GroveState.BLIGHTED);
        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.GROWING);
        assertThat(last.plot().state()).isEqualTo(PlotState.ROOTED);
        verify(ownerProvider, never()).ripenFruit(any(), any());
    }

    @Test
    void startGrove_fruitWithoutContainer_blightsWithRootedPlot() {
        Grove withContainer = ownerGrove(GroveState.PLANTING, PlotState.DORMANT, FruitState.PICKED);
        Fruit containerless = Fruit.bud(withContainer.id(), withContainer.plot().id(), SEED);
        Grove grove = withContainer.withFruit(containerless);
        List<Transition> transitions = recordTransitions(1);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        lenient().when(ownerProvider.ripenFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.RIPE)));

        groveService.completeStart(grove, ownerProvider);

        assertThat(transitions).extracting(Transition::next)
            .containsExactly(GroveState.GROWING, GroveState.BLIGHTED);
        Transition last = transitions.getLast();
        assertThat(last.expected()).isEqualTo(GroveState.GROWING);
        assertThat(last.plot().state()).isEqualTo(PlotState.ROOTED);
        verify(ownerProvider, never()).ripenFruit(any(),
            org.mockito.ArgumentMatchers.argThat(f -> f.id().equals(containerless.id())));
    }

    @Test
    void asyncStep_abandonsWhenAdvanceReturnsFalse() {
        Grove grove = ownerGrove(GroveState.PLANTING, PlotState.DORMANT, FruitState.PICKED);
        List<Transition> transitions = recordTransitions(0);
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((VmPlot) inv.getArgument(0)).withState(PlotState.ROOTED)));

        groveService.completeStart(grove, ownerProvider);

        assertThat(transitions).singleElement().extracting(Transition::next).isEqualTo(GroveState.GROWING);
        verify(fruitRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(ownerProvider).water(any());
        verifyNoMoreInteractions(ownerProvider);
    }

    @Test
    void asyncStop_abandonsWhenAdvanceReturnsFalse() {
        Grove grove = ownerGrove(GroveState.DORMANT, PlotState.ROOTED, FruitState.RIPE);
        List<Transition> transitions = recordTransitions(0);
        when(ownerProvider.pickFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.PICKED)));

        groveService.completeStop(grove, ownerProvider);

        assertThat(transitions).singleElement().extracting(Transition::next).isEqualTo(GroveState.DORMANT);
        verify(fruitRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(ownerProvider).pickFruit(any(), any());
        verifyNoMoreInteractions(ownerProvider);
    }

    /** Gates the afterCommit wiring, which the direct completeStop/completeStart calls bypass. */
    @Test
    void stopAndStart_dispatchAsyncWorkAfterCommit() {
        Grove running = ownerGrove(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        Grove stopped = ownerGrove(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        stubStored(running);
        stubStored(stopped);
        recordTransitions(1);
        when(ownerProvider.pickFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.PICKED)));
        when(ownerProvider.dormant(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Plot) inv.getArgument(0)).withState(PlotState.DORMANT)));
        when(ownerProvider.water(any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Plot) inv.getArgument(0)).withState(PlotState.ROOTED)));
        when(ownerProvider.ripenFruit(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
            ((Fruit) inv.getArgument(1)).withState(FruitState.RIPE)));

        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                .thenAnswer(invocation -> {
                    ((TransactionSynchronization) invocation.getArgument(0)).afterCommit();
                    return null;
                });

            groveService.stopGrove(running.id());
            groveService.startGrove(stopped.id());

            verify(ownerProvider, timeout(2000)).dormant(
                org.mockito.ArgumentMatchers.argThat(p -> p.id().equals(running.plot().id())));
            verify(ownerProvider, timeout(2000)).ripenFruit(
                org.mockito.ArgumentMatchers.argThat(p -> p.id().equals(stopped.plot().id())), any());
        }
    }

    @Test
    void clearGrove_clearingGrove_rejectedWithoutTeardown() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));

        groveService.clearGrove(grove.id());

        verify(groveRepository, never()).save(any());
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void clearGrove_clearedGrove_isNoOp() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARED);
        when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));

        groveService.clearGrove(grove.id());

        verify(groveRepository, never()).save(any());
        verifyNoInteractions(providerRegistry);
    }

    @Test
    void clearGrove_orphanedGrove_acceptedAndProceeds() {
        try (MockedStatic<TransactionSynchronizationManager> tsm = mockStatic(TransactionSynchronizationManager.class)) {
            Grove grove = groveWithPlot();
            GroveEntity entity = entityFor(grove, GroveState.ORPHANED);
            when(groveRepository.findById(grove.id())).thenReturn(Optional.of(entity));

            groveService.clearGrove(grove.id());

            ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
            verify(groveRepository).save(saved.capture());
            assertThat(saved.getValue().getState()).isEqualTo(GroveState.CLEARING);
            tsm.verify(() -> TransactionSynchronizationManager.registerSynchronization(any()));
        }
    }

    /** A freshly germinated VM plot: not yet ROOTED and without a host. */
    private Grove groveWithPlot() {
        Grove grove = Grove.plant(
            UUID.randomUUID(), "g", "https://example.invalid/r.git", "main");
        return grove.withPlot(VmPlot.germinate(grove.id(), "test-default",
            SeedlingSpec.from(new VmSpec(2, 4096, 20, "small", null))));
    }

    private static Grove withGerminatingPlot(Grove grove) {
        return grove.withPlot(VmPlot.germinate(grove.id(), "test-default", SeedlingSpec.from(VmSpec.small())));
    }

    private GroveEntity entityFor(Grove grove, GroveState state) {
        return GroveEntity.fromModel(grove.withState(state));
    }

    @Test
    void tearDownAndRecord_marksOrphanedWhenUprootFails() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        // Prove the failure came from uproot, not from something incidental upstream.
        verify(provider).uproot(any());
        ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
        verify(groveRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(GroveState.ORPHANED);
    }

    /**
     * Guards the original defect: CLEARED was set in a `finally`, so failure and success
     * produced the same terminal state. "not CLEARED" is the load-bearing half — "ORPHANED"
     * alone would still pass if some other non-terminal state were set.
     */
    @Test
    void tearDownAndRecord_neverMarksClearedOnFailure() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        // The captor holds one mutable GroveEntity, so getAllValues() would return N aliases of
        // the same final-state object. Record the state AT SAVE TIME instead.
        List<GroveState> atSave = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(groveRepository.save(any())).thenAnswer(inv -> {
            atSave.add(((GroveEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        verify(provider).uproot(any());
        verify(groveRepository, atLeastOnce()).save(any());
        assertThat(atSave).doesNotContain(GroveState.CLEARED);
    }

    /**
     * Retaining the rows is the point: they name what leaked. The old ordering deleted them
     * before uproot, destroying the only record of the orphaned resource.
     */
    @Test
    void tearDownAndRecord_retainsFruitRowsWhenTeardownFails() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("provider gone")));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        verify(provider).uproot(any());
        verify(fruitRepository, never()).deleteAllById(any());
    }

    @Test
    void tearDownAndRecord_marksClearedAndDeletesFruitWhenTeardownSucceeds() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        ArgumentCaptor<GroveEntity> saved = ArgumentCaptor.forClass(GroveEntity.class);
        verify(groveRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(GroveState.CLEARED);
        verify(fruitRepository).deleteAllById(any());
    }

    /**
     * Guards the generation constraint: deleteAllById must receive the ids captured in
     * {@code grove.fruits()}, not a re-query result. findByGroveId is left unstubbed on purpose —
     * a regression to re-querying would yield an empty list and fail this assertion.
     */
    @Test
    void tearDownAndRecord_deletesOnlyCapturedFruitGeneration() {
        Seed seed = Seed.devcontainer().name("test").image("ubuntu").build();
        Grove grove = groveWithPlot();
        Fruit fruit = Fruit.bud(grove.id(), grove.plot().id(), seed);
        grove = grove.withFruit(fruit);
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> deletedIds = ArgumentCaptor.forClass(List.class);
        verify(fruitRepository).deleteAllById(deletedIds.capture());
        assertThat(deletedIds.getValue()).containsExactly(fruit.id());
    }

    /**
     * Guards the Phase 1 / Phase 2 split: bookkeeping failing after the substrate was released
     * must not report ORPHANED, since nothing leaked. In-process only — if Phase 2's own
     * {@code save} fails, the row keeps CLEARING and the reconciler marks it ORPHANED later.
     */
    @Test
    void tearDownAndRecord_doesNotReportOrphanedWhenOnlyBookkeepingFails() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(groveRepository.save(any())).thenThrow(new RuntimeException("db down"));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        verify(provider).uproot(any());
        assertThat(entity.getState()).isNotEqualTo(GroveState.ORPHANED);
    }

    /**
     * Ordering guard: teardown must precede record deletion, which is what makes a failed
     * teardown recoverable.
     */
    @Test
    void tearDownAndRecord_uprootsBeforeDeletingFruitRows() {
        Grove grove = groveWithPlot();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        GroveProvider provider = mock(GroveProvider.class);
        when(providerRegistry.require("test-default")).thenReturn(provider);
        when(provider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        InOrder order = inOrder(provider, fruitRepository);
        order.verify(provider).uproot(any());
        order.verify(fruitRepository).deleteAllById(any());
    }

    private Grove ownerGroveWithContainerFruits(String... containerIds) {
        Grove grove = Grove.plant(UUID.randomUUID(), "g", "https://example.invalid/r.git", "main");
        grove = grove.withPlot(VmPlot.germinate(grove.id(), "owner", SeedlingSpec.from(VmSpec.small())));
        Seed seed = Seed.devcontainer().name("test").image("ubuntu").build();
        for (String containerId : containerIds) {
            grove = grove.withFruit(Fruit.bud(grove.id(), grove.plot().id(), seed)
                .withContainerDetails(containerId, List.of()));
        }
        return grove;
    }

    private static Plot sapling(Plot plot) {
        return plot.withState(PlotState.ROOTED);
    }

    @Test
    void tearDown_compostFailure_stillUproots() {
        Grove grove = ownerGroveWithContainerFruits("c1", "c2");
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        List<GroveState> atSave = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(groveRepository.save(any())).thenAnswer(inv -> {
            atSave.add(((GroveEntity) inv.getArgument(0)).getState());
            return inv.getArgument(0);
        });
        when(ownerProvider.inspect(any())).thenReturn(
            CompletableFuture.completedFuture(sapling(grove.plot())));
        when(ownerProvider.compostFruit(any(), any())).thenAnswer(inv ->
            "c1".equals(((Fruit) inv.getArgument(1)).containerId())
                ? CompletableFuture.failedFuture(new IllegalStateException("sshd down"))
                : CompletableFuture.completedFuture(null));
        when(ownerProvider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        InOrder order = inOrder(ownerProvider);
        order.verify(ownerProvider).compostFruit(any(), org.mockito.ArgumentMatchers.argThat(f -> "c1".equals(f.containerId())));
        order.verify(ownerProvider).compostFruit(any(), org.mockito.ArgumentMatchers.argThat(f -> "c2".equals(f.containerId())));
        order.verify(ownerProvider).uproot(any());
        assertThat(atSave).contains(GroveState.CLEARED).doesNotContain(GroveState.ORPHANED);
    }

    @Test
    void tearDown_inspectThrows_skipsCompostAndUproots() {
        Grove grove = ownerGroveWithContainerFruits("c1");
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        when(ownerProvider.inspect(any())).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("gone")));
        when(ownerProvider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        verify(ownerProvider, never()).compostFruit(any(), any());
        verify(ownerProvider).uproot(any());
        assertThat(entity.getState()).isEqualTo(GroveState.CLEARED);
    }

    @Test
    void tearDown_unregisteredOwner_marksOrphanedAndKeepsRow() {
        Grove grove = ownerGroveWithContainerFruits("c1");
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        when(providerRegistry.require("owner")).thenThrow(new IllegalStateException("no provider"));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        assertThat(entity.getState()).isEqualTo(GroveState.ORPHANED);
        verify(groveRepository).save(entity);
        verify(fruitRepository, never()).deleteAllById(any());
    }

    @Test
    void tearDown_success_persistsClearedWithWitheredPlotInOneSave() {
        Grove grove = ownerGroveWithContainerFruits();
        GroveEntity entity = entityFor(grove, GroveState.CLEARING);
        List<PlotState> plotStatesAtSave = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<GroveState> statesAtSave = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(groveRepository.save(any())).thenAnswer(inv -> {
            GroveEntity saved = inv.getArgument(0);
            statesAtSave.add(saved.getState());
            plotStatesAtSave.add(saved.plot().state());
            return saved;
        });
        when(ownerProvider.uproot(any())).thenReturn(CompletableFuture.completedFuture(null));

        groveService.tearDownAndRecord(grove.id(), grove, entity);

        assertThat(statesAtSave).containsExactly(GroveState.CLEARED);
        assertThat(plotStatesAtSave).containsExactly(PlotState.WITHERED);
        assertThat(entity.plot().id()).isEqualTo(grove.plot().id());
    }
}
