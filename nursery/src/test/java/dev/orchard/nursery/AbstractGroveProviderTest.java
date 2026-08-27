package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractGroveProviderTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static Seedling germinated() {
        return Seedling.germinate(UUID.randomUUID(), Seedling.SeedlingSpec.small());
    }

    private record FakeLaunch(String instanceId, int port) {}

    /** Records step order and can be told to fail at a named step. */
    private static class RecordingProvider extends AbstractGroveProvider<FakeLaunch> {
        final List<String> calls = new ArrayList<>();
        String throwAtStep;

        RecordingProvider(ExecutorService executor) {
            super(executor, new FruitGrower());
        }

        private void step(String name) {
            calls.add(name);
            if (name.equals(throwAtStep)) {
                throw new IllegalStateException("boom at " + name);
            }
        }

        @Override
        protected FakeLaunch launch(Seedling seedling) {
            step("launch");
            return new FakeLaunch("fake-instance-1", 2200);
        }

        @Override
        protected void awaitRunning(Seedling seedling, FakeLaunch launched) {
            step("awaitRunning");
        }

        @Override
        protected PlantedSeedling resolveEndpoint(Seedling seedling, FakeLaunch launched) {
            step("resolveEndpoint");
            return new PlantedSeedling(launched.instanceId(), "192.168.1.50", launched.port());
        }

        @Override
        protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
            step("awaitReachable");
        }

        @Override
        public String getProviderId() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<Seedling> water(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> dormant(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> inspect(Seedling seedling) {
            throw new UnsupportedOperationException();
        }
    }

    /** Implements only the three abstract steps — deliberately does not override awaitRunning. */
    private static class NoHookProvider extends AbstractGroveProvider<String> {

        NoHookProvider(ExecutorService executor) {
            this(executor, new FruitGrower());
        }

        NoHookProvider(ExecutorService executor, FruitGrower fruitGrower) {
            super(executor, fruitGrower);
        }

        @Override
        protected String launch(Seedling seedling) {
            return "no-hook-instance";
        }

        @Override
        protected PlantedSeedling resolveEndpoint(Seedling seedling, String launched) {
            return new PlantedSeedling(launched, "172.16.0.9", 22);
        }

        @Override
        protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
        }

        @Override
        public String getProviderId() {
            return "no-hook";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<Seedling> water(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> dormant(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> inspect(Seedling seedling) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void plantSubstrate_happyPath_runsStepsInOrder() {
        RecordingProvider provider = new RecordingProvider(executor);

        provider.plantSubstrate(germinated()).join();

        assertThat(provider.calls)
            .containsExactly("launch", "awaitRunning", "resolveEndpoint", "awaitReachable");
    }

    @Test
    void plantSubstrate_happyPath_returnsSaplingWithResolvedEndpoint() {
        RecordingProvider provider = new RecordingProvider(executor);

        Seedling result = provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("fake-instance-1");
        assertThat(result.ipAddress()).isEqualTo("192.168.1.50");
        assertThat(result.sshPort()).isEqualTo(2200);
        assertThat(result.readyAt()).isNotNull();
    }

    @Test
    void plantSubstrate_launchThrows_returnsBlightedAndSkipsRemainingSteps() {
        RecordingProvider provider = new RecordingProvider(executor);
        provider.throwAtStep = "launch";

        Seedling result = provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        assertThat(provider.calls).containsExactly("launch");
    }

    @Test
    void plantSubstrate_awaitReachableThrows_returnsBlightedWithoutEndpointApplied() {
        RecordingProvider provider = new RecordingProvider(executor);
        provider.throwAtStep = "awaitReachable";

        Seedling result = provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        assertThat(result.ipAddress()).isNull();
        assertThat(provider.calls)
            .containsExactly("launch", "awaitRunning", "resolveEndpoint", "awaitReachable");
    }

    @Test
    void plantSubstrate_awaitRunningNotOverridden_defaultHookIsNoOpAndSeedlingStillReachesSapling() {
        NoHookProvider provider = new NoHookProvider(executor);

        Seedling result = provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("no-hook-instance");
        assertThat(result.ipAddress()).isEqualTo("172.16.0.9");
        assertThat(result.sshPort()).isEqualTo(22);
    }

    @Test
    void plantSubstrate_isFinal_soSubclassesCannotBypassSharedStateAndErrorHandling() throws Exception {
        assertThat(Modifier.isFinal(
                AbstractGroveProvider.class.getMethod("plantSubstrate", Seedling.class).getModifiers()))
            .as("plantSubstrate() must stay final — a substrate that cannot fit the steps should "
                + "implement GroveProvider directly instead of overriding this")
            .isTrue();
    }

    // --- Shared VM behaviour, rehomed here when VmGroveProvider was collapsed away (#86) ---

    private static Fruit budded(Seedling seedling) {
        // Seed must be non-null: Fruit.bud dereferences seed.name() at Fruit.java:40.
        return Fruit.bud(seedling.groveId(), seedling.id(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());
    }

    @Test
    void growFruit_delegatesToTheInjectedFruitGrower() {
        FruitGrower fruitGrower = mock(FruitGrower.class);
        Seedling s = TestSeedlings.fake();
        Fruit f = budded(s);
        when(fruitGrower.grow(s, f)).thenReturn(CompletableFuture.completedFuture(f));

        assertThat(new NoHookProvider(executor, fruitGrower).growFruit(s, f).join()).isSameAs(f);
        verify(fruitGrower).grow(s, f);
    }

    @Test
    void compostFruit_delegatesToTheInjectedFruitGrower() {
        FruitGrower fruitGrower = mock(FruitGrower.class);
        Seedling s = TestSeedlings.fake();
        Fruit f = budded(s);
        when(fruitGrower.compost(s, f)).thenReturn(CompletableFuture.completedFuture(null));

        new NoHookProvider(executor, fruitGrower).compostFruit(s, f).join();

        verify(fruitGrower).compost(s, f);
    }

    @Test
    void vine_isSshBackedForVmSubstrates() {
        assertThat(new NoHookProvider(executor).vine(TestSeedlings.fake()))
            .isInstanceOf(SshVine.class);
    }

    @Test
    void vine_isAvailableBeforeTheSubstrateIsRoutable() {
        // A GERMINATING seedling has no endpoint yet. Obtaining a vine must not require one:
        // provisioning and diagnostic exec both run before the grove is routable (#215).
        Seedling germinating = Seedling.germinate(UUID.randomUUID(), Seedling.SeedlingSpec.small());

        assertThat(germinating.state()).isEqualTo(SeedlingState.GERMINATING);
        assertThat(germinating.ipAddress()).isNull();

        // Must not throw, and must yield a usable command channel — not merely a non-null object.
        Vine vine = new NoHookProvider(executor).vine(germinating);

        assertThat(vine).isNotNull();
        assertThat(vine.commands()).isNotNull();
    }

    @Test
    void vine_returnsAFreshInstancePerCallSoAnUpdatedEndpointIsPickedUp() {
        NoHookProvider provider = new NoHookProvider(executor);
        Seedling seedling = TestSeedlings.fake();

        assertThat(provider.vine(seedling)).isNotSameAs(provider.vine(seedling));
    }

    @Test
    void failureContext_launchNeverSucceeded_isEmpty() {
        NoHookProvider provider = new NoHookProvider(executor);

        assertThat(provider.failureContext(null)).isEmpty();
    }

    @Test
    void failureContext_launchSucceeded_rendersLaunchResultSoLeakedResourcesAreIdentifiable() {
        NoHookProvider provider = new NoHookProvider(executor);

        assertThat(provider.failureContext("i-abc123")).contains("i-abc123");
    }

    /** Captures the thread name a step ran on, to prove {@code plantSubstrate()} dispatches onto the executor. */
    private static class ThreadNameCapturingProvider extends AbstractGroveProvider<FakeLaunch> {
        volatile String capturedThreadName;

        ThreadNameCapturingProvider(ExecutorService executor) {
            super(executor, new FruitGrower());
        }

        @Override
        protected FakeLaunch launch(Seedling seedling) {
            capturedThreadName = Thread.currentThread().getName();
            return new FakeLaunch("fake-instance-thread-check", 2200);
        }

        @Override
        protected PlantedSeedling resolveEndpoint(Seedling seedling, FakeLaunch launched) {
            return new PlantedSeedling(launched.instanceId(), "192.168.1.51", launched.port());
        }

        @Override
        protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
        }

        @Override
        public String getProviderId() {
            return "thread-name-capture";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<Seedling> water(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> dormant(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> inspect(Seedling seedling) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void plantSubstrate_dispatchesOnSuppliedExecutorRatherThanCommonPool() throws Exception {
        ExecutorService pinnedExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "sdd-pinned-executor"));
        try {
            ThreadNameCapturingProvider provider = new ThreadNameCapturingProvider(pinnedExecutor);

            provider.plantSubstrate(germinated()).join();

            assertThat(provider.capturedThreadName)
                .as("plantSubstrate() must run its work on the executor supplied to the "
                    + "constructor, not the ForkJoinPool common pool, since providers block for "
                    + "minutes inside awaitReachable")
                .isEqualTo("sdd-pinned-executor");
        } finally {
            pinnedExecutor.shutdownNow();
        }
    }

    /**
     * Fails after {@link #launch} succeeds and records what {@code failureContext} was invoked
     * with, to prove the launch result reaches the failure log rather than just being reachable
     * via a direct call.
     */
    private static class PostLaunchFailureProvider extends AbstractGroveProvider<FakeLaunch> {
        private static final FakeLaunch NOT_CAPTURED = new FakeLaunch("not-captured", -1);
        volatile FakeLaunch capturedFailureContextArg = NOT_CAPTURED;

        PostLaunchFailureProvider(ExecutorService executor) {
            super(executor, new FruitGrower());
        }

        @Override
        protected FakeLaunch launch(Seedling seedling) {
            return new FakeLaunch("i-orphaned-instance", 2200);
        }

        @Override
        protected PlantedSeedling resolveEndpoint(Seedling seedling, FakeLaunch launched) throws Exception {
            throw new IllegalStateException("boom after launch succeeded");
        }

        @Override
        protected void awaitReachable(Seedling seedling, PlantedSeedling planted) {
        }

        @Override
        protected String failureContext(FakeLaunch launched) {
            capturedFailureContextArg = launched;
            return super.failureContext(launched);
        }

        @Override
        public String getProviderId() {
            return "post-launch-failure";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<Seedling> water(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> dormant(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Seedling seedling) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Seedling> inspect(Seedling seedling) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void plantSubstrate_failsAfterLaunchSucceeded_passesLaunchResultToFailureContextForOperatorCleanup() {
        PostLaunchFailureProvider provider = new PostLaunchFailureProvider(executor);

        Seedling result = provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(SeedlingState.BLIGHTED);
        assertThat(provider.capturedFailureContextArg)
            .as("failureContext must be invoked with the non-null launch result during the real "
                + "plantSubstrate() failure path so an operator can identify and terminate an orphaned "
                + "billable instance from the failure log")
            .isEqualTo(new FakeLaunch("i-orphaned-instance", 2200));
    }
}
