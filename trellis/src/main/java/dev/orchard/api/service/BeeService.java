package dev.orchard.api.service;

import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.event.BeeStateChangedEvent;
import dev.orchard.apiary.BeeKeeper;
import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.core.model.*;
import dev.orchard.nursery.CommandRunner;
import dev.orchard.nursery.SshExecutor;
import dev.orchard.roots.entity.BeeEntity;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.BeeRepository;
import dev.orchard.roots.repository.GroveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class BeeService {

    private static final Logger log = LoggerFactory.getLogger(BeeService.class);

    private final BeeKeeperRegistry beeKeeperRegistry;
    private final BeeRepository beeRepository;
    private final GroveRepository groveRepository;
    private final CredentialResolver credentialResolver;
    private final ApplicationEventPublisher eventPublisher;

    public BeeService(BeeKeeperRegistry beeKeeperRegistry, BeeRepository beeRepository,
            GroveRepository groveRepository, CredentialResolver credentialResolver,
            ApplicationEventPublisher eventPublisher) {
        this.beeKeeperRegistry = beeKeeperRegistry;
        this.beeRepository = beeRepository;
        this.groveRepository = groveRepository;
        this.credentialResolver = credentialResolver;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Bee attachBee(UUID groveId, UUID cultivatorId, CreateBeeRequest request) {
        GroveEntity groveEntity = groveRepository.findById(groveId)
            .orElseThrow(() -> new NoSuchElementException("Grove not found: " + groveId));

        if (groveEntity.getState() != GroveState.FLOURISHING) {
            throw new IllegalStateException(
                "Grove " + groveId + " must be FLOURISHING to attach a bee, current state: " + groveEntity.getState());
        }

        BeeKeeper beeKeeper = beeKeeperRegistry.get(request.beeType())
            .orElseThrow(() -> new IllegalArgumentException(
                "No BeeKeeper registered for bee type: " + request.beeType()));

        Map<String, String> credentials = credentialResolver.resolve(cultivatorId, request.beeType());
        Map<String, String> configOverrides = request.configOverrides() != null ? request.configOverrides() : Map.of();
        Map<String, String> mergedConfig = new HashMap<>(configOverrides);
        mergedConfig.putAll(credentials);

        BeeSpec spec = new BeeSpec(request.beeType(), request.version(), mergedConfig);
        Bee bee = Bee.hatching(groveId, spec);

        BeeEntity entity = BeeEntity.fromModel(bee);
        beeRepository.save(entity);

        final Bee beeToProvision = bee;
        final BeeKeeper keeper = beeKeeper;
        final CommandRunner runner = commandRunnerFor(groveEntity);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CompletableFuture.runAsync(() -> provisionBee(beeToProvision, keeper, runner));
            }
        });

        return bee;
    }

    @Transactional(readOnly = true)
    public Optional<Bee> getBee(UUID beeId) {
        return beeRepository.findById(beeId).map(BeeEntity::toModel);
    }

    @Transactional(readOnly = true)
    public List<Bee> listBees(UUID groveId) {
        return beeRepository.findByGroveId(groveId).stream()
            .map(BeeEntity::toModel)
            .toList();
    }

    @Transactional
    public Optional<Bee> wake(UUID beeId) {
        return beeRepository.findById(beeId).map(entity -> {
            Bee bee = entity.toModel();
            if (bee.state() != BeeState.HIBERNATING) {
                log.warn("Cannot wake bee {} in state {}", beeId, bee.state());
                return bee;
            }

            BeeKeeper keeper = beeKeeperRegistry.get(bee.type()).orElse(null);
            if (keeper == null) {
                log.warn("No BeeKeeper registered for bee type {}", bee.type());
                return bee;
            }

            GroveEntity groveEntity = groveRepository.findById(bee.groveId()).orElse(null);
            if (groveEntity == null) {
                log.warn("Grove {} not found for bee {}, cannot wake", bee.groveId(), beeId);
                return bee;
            }
            CommandRunner runner = commandRunnerFor(groveEntity);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(() -> releaseBee(bee, keeper, runner));
                }
            });

            return bee;
        });
    }

    @Transactional
    public Optional<Bee> smoke(UUID beeId) {
        return beeRepository.findById(beeId).map(entity -> {
            Bee bee = entity.toModel();
            BeeState previousState = bee.state();

            entity.setState(BeeState.SMOKED);
            beeRepository.save(entity);

            Bee smokedBee = bee.withState(BeeState.SMOKED).withStoppedAt();

            BeeKeeper keeper = beeKeeperRegistry.get(bee.type()).orElse(null);
            GroveEntity groveEntity = keeper != null ? groveRepository.findById(bee.groveId()).orElse(null) : null;
            CommandRunner runner = groveEntity != null ? commandRunnerFor(groveEntity) : null;

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (keeper != null && runner != null) {
                        keeper.smoke(bee, runner).exceptionally(ex -> {
                            log.warn("BeeKeeper smoke command failed for bee {}", beeId, ex);
                            return null;
                        });
                    } else {
                        log.warn("Skipping keeper.smoke() for bee {}: no registered keeper or grove", beeId);
                    }
                    eventPublisher.publishEvent(BeeStateChangedEvent.of(
                        smokedBee.id(), smokedBee.groveId(), previousState, BeeState.SMOKED));
                }
            });

            return smokedBee;
        });
    }

    private void provisionBee(Bee bee, BeeKeeper keeper, CommandRunner runner) {
        BeeSpec spec = bee.spec();
        installBee(bee, keeper, spec, runner)
            .thenCompose(releasedBee -> releaseBee(releasedBee, keeper, runner))
            .exceptionally(ex -> {
                log.error("Bee provisioning failed for bee {}", bee.id(), ex);
                updateBeeState(bee.id(), BeeState.SMOKED, bee.state());
                return null;
            });
    }

    private CompletableFuture<Bee> installBee(Bee bee, BeeKeeper keeper, BeeSpec spec, CommandRunner runner) {
        return keeper.install(bee, spec, runner).thenCompose(installedBee -> {
            updateBeeState(bee.id(), BeeState.HIBERNATING, bee.state());
            return CompletableFuture.completedFuture(installedBee);
        });
    }

    private CompletableFuture<Bee> releaseBee(Bee bee, BeeKeeper keeper, CommandRunner runner) {
        return keeper.release(bee, runner).thenCompose(releasedBee -> {
            BeeState previousState = BeeState.HIBERNATING;
            BeeEntity entity = beeRepository.findById(bee.id()).orElseThrow();
            entity.setState(BeeState.BUZZING);
            entity.setProcessId(releasedBee.processId());
            entity.setStartedAt(releasedBee.startedAt());
            beeRepository.save(entity);

            eventPublisher.publishEvent(BeeStateChangedEvent.of(
                bee.id(), bee.groveId(), previousState, BeeState.BUZZING));

            return CompletableFuture.completedFuture(releasedBee);
        });
    }

    private void updateBeeState(UUID beeId, BeeState newState, BeeState previousState) {
        try {
            BeeEntity entity = beeRepository.findById(beeId).orElseThrow();
            entity.setState(newState);
            beeRepository.save(entity);
            eventPublisher.publishEvent(BeeStateChangedEvent.of(
                beeId, entity.getGroveId(), previousState, newState));
        } catch (Exception e) {
            log.error("Failed to update bee state for bee {}", beeId, e);
        }
    }

    private CommandRunner commandRunnerFor(GroveEntity groveEntity) {
        Seedling.SeedlingSpec spec = new Seedling.SeedlingSpec(
            groveEntity.getSeedlingCpuCores() != null ? groveEntity.getSeedlingCpuCores() : 0,
            groveEntity.getSeedlingMemoryMb() != null ? groveEntity.getSeedlingMemoryMb() : 0,
            groveEntity.getSeedlingDiskGb() != null ? groveEntity.getSeedlingDiskGb() : 0,
            null,
            null
        );
        Seedling seedling = new Seedling(
            groveEntity.getSeedlingId(),
            groveEntity.getId(),
            groveEntity.getSeedlingProviderInstanceId(),
            groveEntity.getSeedlingIpAddress(),
            groveEntity.getSeedlingSshPort() != null ? groveEntity.getSeedlingSshPort() : 22,
            groveEntity.getSeedlingState(),
            spec,
            groveEntity.getPlantedAt(),
            null
        );
        return new SshExecutor(seedling);
    }
}
