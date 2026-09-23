package dev.orchard.api.service;

import dev.orchard.api.event.BeeRemovedEvent;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeState;
import dev.orchard.core.model.BeeType;
import dev.orchard.roots.entity.BeeEntity;
import dev.orchard.roots.repository.BeeRepository;
import dev.orchard.trellis.OrchardApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrchardApplication.class)
@ActiveProfiles("devserver")
@Import(BeeEventCommitOrderingTest.ObserverConfiguration.class)
class BeeEventCommitOrderingTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:orchard-bee-ordering-" + System.nanoTime());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("orchard.nursery.provider", () -> "none");
    }

    /**
     * Collects removal events as they are delivered. Registered as a {@code @TestConfiguration}
     * bean rather than a {@code @Component} so it is not picked up by component scanning and
     * does not leak into other Spring tests' contexts.
     */
    @TestConfiguration
    static class ObserverConfiguration {
        @Bean
        RemovalObserver removalObserver() {
            return new RemovalObserver();
        }
    }

    static class RemovalObserver {
        private final List<UUID> delivered = new CopyOnWriteArrayList<>();

        @EventListener
        void onBeeRemoved(BeeRemovedEvent event) {
            delivered.add(event.beeId());
        }

        List<UUID> delivered() {
            return delivered;
        }
    }

    @Autowired private BeeService beeService;
    @Autowired private BeeRepository beeRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RemovalObserver observer;

    @Test
    void removalEventIsWithheldUntilTheOuterTransactionCommits() {
        UUID groveId = UUID.randomUUID();
        BeeEntity bee = persistBee(groveId, BeeState.HIBERNATING);
        observer.delivered().clear();

        transactionTemplate.execute(status -> {
            assertThat(beeService.removeBee(groveId, bee.getId())).isTrue();

            // Still inside the transaction that did the delete. A direct publishEvent would
            // have delivered here; deferring to afterCommit must not have.
            assertThat(observer.delivered())
                .as("no event may be delivered before the transaction commits")
                .isEmpty();
            return null;
        });

        assertThat(observer.delivered()).containsExactly(bee.getId());
        assertThat(beeRepository.findById(bee.getId())).isEmpty();
    }

    @Test
    void aRolledBackRemovalPublishesNothing() {
        UUID groveId = UUID.randomUUID();
        BeeEntity bee = persistBee(groveId, BeeState.SMOKED);
        observer.delivered().clear();

        try {
            transactionTemplate.execute(status -> {
                beeService.removeBee(groveId, bee.getId());
                status.setRollbackOnly();
                return null;
            });
        } catch (RuntimeException ignored) {
            // rollback-only can surface as an exception depending on the manager; either way
            // the assertion below is what matters.
        }

        assertThat(observer.delivered())
            .as("a rolled-back removal must emit no event")
            .isEmpty();
        assertThat(beeRepository.findById(bee.getId())).isPresent();
    }

    private BeeEntity persistBee(UUID groveId, BeeState state) {
        Bee bee = Bee.hatching(groveId, BeeSpec.of(BeeType.CLAUDE_CODE)).withState(state);
        BeeEntity entity = BeeEntity.fromModel(bee);
        return transactionTemplate.execute(status -> beeRepository.save(entity));
    }
}
