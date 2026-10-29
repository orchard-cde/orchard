package dev.orchard.api.service;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.roots.entity.FruitEntity;
import dev.orchard.roots.entity.GroveEntity;
import dev.orchard.roots.repository.FruitRepository;
import dev.orchard.roots.repository.GroveRepository;
import dev.orchard.trellis.OrchardApplication;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clear that commits while a stop or start is blocked between async steps must win: the
 * stop/start's later writes are conditional on the state it left, so they find nothing to update.
 * Runs against real H2 so the conditional update, not a mock, is what decides.
 */
@SpringBootTest(classes = OrchardApplication.class)
@ActiveProfiles("devserver")
@Import(GroveStopClearInterleavingTest.GateProviderConfiguration.class)
class GroveStopClearInterleavingTest {

    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final String PROVIDER_ID = "interleaving-gate";

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:orchard-stop-clear-" + System.nanoTime());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("orchard.nursery.provider", () -> "none");
        registry.add("orchard.security.oauth2.enabled", () -> "false");
    }

    @TestConfiguration
    static class GateProviderConfiguration {
        @Bean
        GateProvider gateProvider(ProviderRegistry registry) {
            GateProvider provider = new GateProvider();
            registry.register(provider);
            return provider;
        }
    }

    /**
     * Blocks {@code dormant} or {@code water} on a test-controlled future and records the worker
     * thread that called it, so the test can tell when that async step has fully returned.
     */
    static class GateProvider implements GroveProvider {
        volatile boolean gateDormant;
        volatile boolean gateWater;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CompletableFuture<Plot> gate = new CompletableFuture<>();
        volatile Plot gatedPlot;
        volatile Thread worker;
        final AtomicInteger ripenCalls = new AtomicInteger();

        void reset() {
            gateDormant = false;
            gateWater = false;
            entered = new CountDownLatch(1);
            gate = new CompletableFuture<>();
            gatedPlot = null;
            worker = null;
            ripenCalls.set(0);
        }

        private CompletableFuture<Plot> block(Plot plot) {
            gatedPlot = plot;
            worker = Thread.currentThread();
            entered.countDown();
            return gate;
        }

        @Override public ProviderType type() { return ProviderType.QEMU_LOCAL; }
        @Override public String getProviderId() { return PROVIDER_ID; }
        @Override public boolean isAvailable() { return true; }

        @Override
        public Plot reserve(UUID groveId, SeedlingSpec seedlingSpec) {
            throw new UnsupportedOperationException("reserve");
        }

        @Override
        public CompletableFuture<Plot> plantSubstrate(Plot draft) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("plantSubstrate"));
        }

        @Override
        public CompletableFuture<Plot> water(Plot plot) {
            return gateWater ? block(plot) : CompletableFuture.completedFuture(plot.withState(PlotState.ROOTED));
        }

        @Override
        public CompletableFuture<Plot> dormant(Plot plot) {
            return gateDormant ? block(plot) : CompletableFuture.completedFuture(plot.withState(PlotState.DORMANT));
        }

        @Override
        public CompletableFuture<Void> uproot(Plot plot) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Plot> inspect(Plot plot) {
            return CompletableFuture.completedFuture(plot);
        }

        @Override
        public CompletableFuture<Fruit> growFruit(Plot plot, Fruit fruit) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("growFruit"));
        }

        @Override
        public CompletableFuture<Void> compostFruit(Plot plot, Fruit fruit) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Fruit> pickFruit(Plot plot, Fruit fruit) {
            return CompletableFuture.completedFuture(fruit.withState(FruitState.PICKED));
        }

        @Override
        public CompletableFuture<Fruit> ripenFruit(Plot plot, Fruit fruit) {
            ripenCalls.incrementAndGet();
            return CompletableFuture.completedFuture(fruit.withState(FruitState.RIPE));
        }

        @Override
        public Vine vine(Plot plot) {
            throw new UnsupportedOperationException("vine");
        }

        @Override
        public void verifyDevcontainerCli(Plot plot, String expectedVersion) {
            throw new UnsupportedOperationException("verifyDevcontainerCli");
        }
    }

    @Autowired private GroveService groveService;
    @Autowired private GroveRepository groveRepository;
    @Autowired private FruitRepository fruitRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private GateProvider provider;

    @BeforeEach
    void resetProvider() {
        provider.reset();
    }

    @Test
    void clearCommittedMidStop_isNotOverwrittenByTheStop() throws Exception {
        Grove grove = persist(GroveState.FLOURISHING, PlotState.ROOTED, FruitState.RIPE);
        provider.gateDormant = true;

        groveService.stopGrove(grove.id());
        assertThat(provider.entered.await(BOUND.toSeconds(), TimeUnit.SECONDS))
            .as("stop reached dormant").isTrue();

        clearAndAwaitCleared(grove.id());
        provider.gate.complete(provider.gatedPlot.withState(PlotState.DORMANT));
        awaitWorkerLeftGroveService();

        assertClearStands(grove.id());
    }

    @Test
    void clearCommittedMidStart_isNotOverwrittenByTheStart() throws Exception {
        Grove grove = persist(GroveState.DORMANT, PlotState.DORMANT, FruitState.PICKED);
        provider.gateWater = true;

        groveService.startGrove(grove.id());
        assertThat(provider.entered.await(BOUND.toSeconds(), TimeUnit.SECONDS))
            .as("start reached water").isTrue();

        clearAndAwaitCleared(grove.id());
        provider.gate.complete(provider.gatedPlot.withState(PlotState.ROOTED));
        awaitWorkerLeftGroveService();

        assertClearStands(grove.id());
        assertThat(provider.ripenCalls).as("an abandoned start ripens nothing").hasValue(0);
    }

    private Grove persist(GroveState state, PlotState plotState, FruitState fruitState) {
        Grove planted = Grove.plant(UUID.randomUUID(), "g", "https://example.invalid/r.git", "main");
        VmPlot plot = new VmPlot(UUID.randomUUID(), planted.id(), PROVIDER_ID, "vm-1", plotState,
            Instant.now(), null, "127.0.0.1", 22, List.of(), VmSpec.small());
        Seed seed = Seed.devcontainer().name("test").image("ubuntu").build();
        Fruit fruit = Fruit.bud(planted.id(), plot.id(), seed)
            .withContainerDetails("c1", List.of())
            .withState(fruitState);
        Grove grove = planted.withState(state).withPlot(plot).withFruit(fruit);
        transactionTemplate.executeWithoutResult(status -> {
            groveRepository.save(GroveEntity.fromModel(grove));
            fruitRepository.save(FruitEntity.fromModel(fruit));
        });
        return grove;
    }

    /** clearGrove returns before its async teardown; the clear has landed once the row is CLEARED with no fruit rows. */
    private void clearAndAwaitCleared(UUID groveId) throws InterruptedException {
        groveService.clearGrove(groveId);
        awaitTrue("clear landed", () ->
            groveRepository.findById(groveId).orElseThrow().getState() == GroveState.CLEARED
                && fruitRepository.findByGroveId(groveId).isEmpty());
    }

    private void awaitWorkerLeftGroveService() throws InterruptedException {
        Thread worker = provider.worker;
        awaitTrue("stop/start async step returned", () -> Arrays.stream(worker.getStackTrace())
            .noneMatch(frame -> frame.getClassName().startsWith(GroveService.class.getName())));
    }

    private void assertClearStands(UUID groveId) {
        GroveEntity row = groveRepository.findById(groveId).orElseThrow();
        assertThat(row.getState()).isEqualTo(GroveState.CLEARED);
        assertThat(row.plot().state()).isEqualTo(PlotState.WITHERED);
        assertThat(fruitRepository.findByGroveId(groveId)).isEmpty();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(20);
        }
    }
}
