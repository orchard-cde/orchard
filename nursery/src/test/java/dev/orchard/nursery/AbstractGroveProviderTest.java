package dev.orchard.nursery;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotRequest;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmEndpoint;
import dev.orchard.core.model.Host;
import dev.orchard.core.model.ProviderHandle;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractGroveProviderTest {

    /**
     * The runner class an SSH-backed {@link SshVine} hands out. Derived rather than named: the
     * implementation is package-private to {@code :vine} precisely so nothing outside can
     * construct or reference it directly, and asserting "the kind SshVine produces" is the claim
     * these tests actually want.
     */
    private static final Class<?> SSH_RUNNER =
        new SshVine(TestPlots.fake()).commands().getClass();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static VmPlot germinated() {
        return VmPlot.germinate(UUID.randomUUID(), "test", PlotRequest.from(VmSpec.small()));
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
        protected FakeLaunch launch(VmPlot plot) {
            step("launch");
            return new FakeLaunch("fake-instance-1", 2200);
        }

        @Override
        protected void awaitRunning(VmPlot plot, FakeLaunch launched) {
            step("awaitRunning");
        }

        @Override
        protected VmEndpoint resolveEndpoint(VmPlot plot, FakeLaunch launched) {
            step("resolveEndpoint");
            return new VmEndpoint(new ProviderHandle(launched.instanceId()), new Host("192.168.1.50"), launched.port());
        }

        @Override
        protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
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
        public CompletableFuture<Plot> water(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> dormant(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> inspect(Plot plot) {
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
        protected String launch(VmPlot plot) {
            return "no-hook-instance";
        }

        @Override
        protected VmEndpoint resolveEndpoint(VmPlot plot, String launched) {
            return new VmEndpoint(new ProviderHandle(launched), new Host("172.16.0.9"), 22);
        }

        @Override
        protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
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
        public CompletableFuture<Plot> water(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> dormant(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> inspect(Plot plot) {
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

        VmPlot result = (VmPlot) provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("fake-instance-1");
        assertThat(result.host()).isEqualTo("192.168.1.50");
        assertThat(result.sshPort()).isEqualTo(2200);
        assertThat(result.readyAt()).isNotNull();
    }

    @Test
    void plantSubstrate_launchThrows_returnsBlightedAndSkipsRemainingSteps() {
        RecordingProvider provider = new RecordingProvider(executor);
        provider.throwAtStep = "launch";

        VmPlot result = (VmPlot) provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        assertThat(provider.calls).containsExactly("launch");
    }

    @Test
    void plantSubstrate_awaitReachableThrows_returnsBlightedWithoutEndpointApplied() {
        RecordingProvider provider = new RecordingProvider(executor);
        provider.throwAtStep = "awaitReachable";

        VmPlot result = (VmPlot) provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        assertThat(result.host()).isNull();
        assertThat(provider.calls)
            .containsExactly("launch", "awaitRunning", "resolveEndpoint", "awaitReachable");
    }

    @Test
    void plantSubstrate_awaitRunningNotOverridden_defaultHookIsNoOpAndSeedlingStillReachesSapling() {
        NoHookProvider provider = new NoHookProvider(executor);

        VmPlot result = (VmPlot) provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(PlotState.SAPLING);
        assertThat(result.providerInstanceId()).isEqualTo("no-hook-instance");
        assertThat(result.host()).isEqualTo("172.16.0.9");
        assertThat(result.sshPort()).isEqualTo(22);
    }

    @Test
    void plantSubstrate_isFinal_soSubclassesCannotBypassSharedStateAndErrorHandling() throws Exception {
        assertThat(Modifier.isFinal(
                AbstractGroveProvider.class.getMethod("plantSubstrate", Plot.class).getModifiers()))
            .as("plantSubstrate() must stay final — a substrate that cannot fit the steps should "
                + "implement GroveProvider directly instead of overriding this")
            .isTrue();
    }

    // --- Shared VM behaviour ---

    private static Fruit budded(VmPlot plot) {
        // Seed must be non-null: Fruit.bud dereferences seed.name().
        return Fruit.bud(plot.groveId(), plot.id(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());
    }

    /**
     * Matches an {@link ExecTarget} built the way {@link AbstractGroveProvider} builds one for a
     * VM substrate: an SSH-backed runner, the fixed {@code "/workspace"} path, and the given
     * plot's id used purely for correlation.
     */
    private static ArgumentMatcher<ExecTarget> execTargetFor(VmPlot plot) {
        return target -> target.runner().getClass() == SSH_RUNNER
            && target.workspacePath().equals("/workspace")
            && target.targetId().equals(plot.id());
    }

    @Test
    void growFruit_delegatesToTheInjectedFruitGrower() {
        FruitGrower fruitGrower = mock(FruitGrower.class);
        VmPlot s = TestPlots.fake();
        Fruit f = budded(s);
        when(fruitGrower.grow(argThat(execTargetFor(s)), eq(f)))
            .thenReturn(CompletableFuture.completedFuture(f));

        assertThat(new NoHookProvider(executor, fruitGrower).growFruit(s, f).join()).isSameAs(f);
        verify(fruitGrower).grow(argThat(execTargetFor(s)), eq(f));
    }

    @Test
    void compostFruit_delegatesToTheInjectedFruitGrower() {
        FruitGrower fruitGrower = mock(FruitGrower.class);
        VmPlot s = TestPlots.fake();
        Fruit f = budded(s);
        when(fruitGrower.compost(argThat(execTargetFor(s)), eq(f)))
            .thenReturn(CompletableFuture.completedFuture(null));

        new NoHookProvider(executor, fruitGrower).compostFruit(s, f).join();

        verify(fruitGrower).compost(argThat(execTargetFor(s)), eq(f));
    }

    @Test
    void growFruit_theRunnerFruitGrowerReceivesIsTheSameOneDevcontainerCliReceives() throws Exception {
        // Closes the class of defect where AbstractGroveProvider or FruitGrower could substitute
        // a different CommandRunner en route: this asserts a single runner INSTANCE flows from
        // AbstractGroveProvider.growFruit() through FruitGrower.grow() into DevcontainerCli.up(),
        // not merely that "some" runner arrived (#86).
        DevcontainerCli cli = mock(DevcontainerCli.class);
        FruitGrower realFruitGrower = new FruitGrower(cli, true, null);
        FruitGrower fruitGrowerSpy = spy(realFruitGrower);
        VmPlot s = TestPlots.fake();
        Fruit f = budded(s);

        when(cli.up(any(), eq("/workspace"), eq(f.id()), eq(f.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-identity", null, "vscode", "/workspace"));
        when(cli.inspectContainerName(any(), eq("c-identity"))).thenReturn(f.containerName());

        new NoHookProvider(executor, fruitGrowerSpy).growFruit(s, f).join();

        ArgumentCaptor<ExecTarget> viaFruitGrower = ArgumentCaptor.forClass(ExecTarget.class);
        verify(fruitGrowerSpy).grow(viaFruitGrower.capture(), eq(f));

        ArgumentCaptor<CommandRunner> viaCli = ArgumentCaptor.forClass(CommandRunner.class);
        verify(cli).up(viaCli.capture(), eq("/workspace"), eq(f.id()), eq(f.containerName()), any());

        assertThat(viaCli.getValue())
            .as("the CommandRunner DevcontainerCli.up() receives must be the exact same instance "
                + "AbstractGroveProvider handed to FruitGrower.grow() — not merely one of the same type")
            .isSameAs(viaFruitGrower.getValue().runner());
    }

    @Test
    void vine_isSshBackedForVmSubstrates() {
        assertThat(new NoHookProvider(executor).vine(TestPlots.fake()))
            .isInstanceOf(SshVine.class);
    }

    @Test
    void vine_isAvailableBeforeTheSubstrateIsRoutable() {
        // A GERMINATING seedling has no endpoint yet. Obtaining a vine must not require one:
        // provisioning and diagnostic exec both run before the grove is routable (#215).
        VmPlot germinating = VmPlot.germinate(UUID.randomUUID(), "test", PlotRequest.from(VmSpec.small()));

        assertThat(germinating.state()).isEqualTo(PlotState.GERMINATING);
        assertThat(germinating.host()).isNull();

        // Must not throw, and must yield a usable command channel — not merely a non-null object.
        Vine vine = new NoHookProvider(executor).vine(germinating);

        assertThat(vine).isNotNull();
        assertThat(vine.commands()).isNotNull();
    }

    @Test
    void vine_returnsAFreshInstancePerCallSoAnUpdatedEndpointIsPickedUp() {
        NoHookProvider provider = new NoHookProvider(executor);
        VmPlot plot = TestPlots.fake();

        assertThat(provider.vine(plot)).isNotSameAs(provider.vine(plot));
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
        protected FakeLaunch launch(VmPlot plot) {
            capturedThreadName = Thread.currentThread().getName();
            return new FakeLaunch("fake-instance-thread-check", 2200);
        }

        @Override
        protected VmEndpoint resolveEndpoint(VmPlot plot, FakeLaunch launched) {
            return new VmEndpoint(new ProviderHandle(launched.instanceId()), new Host("192.168.1.51"), launched.port());
        }

        @Override
        protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
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
        public CompletableFuture<Plot> water(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> dormant(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> inspect(Plot plot) {
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
        protected FakeLaunch launch(VmPlot plot) {
            return new FakeLaunch("i-orphaned-instance", 2200);
        }

        @Override
        protected VmEndpoint resolveEndpoint(VmPlot plot, FakeLaunch launched) throws Exception {
            throw new IllegalStateException("boom after launch succeeded");
        }

        @Override
        protected void awaitReachable(VmPlot plot, VmEndpoint endpoint) {
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
        public CompletableFuture<Plot> water(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> dormant(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> uproot(Plot plot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Plot> inspect(Plot plot) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void plantSubstrate_failsAfterLaunchSucceeded_passesLaunchResultToFailureContextForOperatorCleanup() {
        PostLaunchFailureProvider provider = new PostLaunchFailureProvider(executor);

        VmPlot result = (VmPlot) provider.plantSubstrate(germinated()).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        assertThat(provider.capturedFailureContextArg)
            .as("failureContext must be invoked with the non-null launch result during the real "
                + "plantSubstrate() failure path so an operator can identify and terminate an orphaned "
                + "billable instance from the failure log")
            .isEqualTo(new FakeLaunch("i-orphaned-instance", 2200));
    }

    private static ContainerPlot containerPlot() {
        return new ContainerPlot(UUID.randomUUID(), UUID.randomUUID(), "docker-local", "c-1",
            PlotState.SAPLING, Instant.now(), null, Path.of("/tmp/ws"), ContainerRuntime.DOCKER,
            new ContainerSpec(2, 2048));
    }

    static Stream<Arguments> templateMethods() {
        Fruit fruit = Fruit.bud(UUID.randomUUID(), UUID.randomUUID(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());
        return Stream.of(
            Arguments.of("plantSubstrate",
                (BiConsumer<GroveProvider, ContainerPlot>) (p, c) -> p.plantSubstrate(c)),
            Arguments.of("growFruit",
                (BiConsumer<GroveProvider, ContainerPlot>) (p, c) -> p.growFruit(c, fruit)),
            Arguments.of("compostFruit",
                (BiConsumer<GroveProvider, ContainerPlot>) (p, c) -> p.compostFruit(c, fruit)),
            Arguments.of("vine",
                (BiConsumer<GroveProvider, ContainerPlot>) (p, c) -> p.vine(c)),
            Arguments.of("verifyDevcontainerCli",
                (BiConsumer<GroveProvider, ContainerPlot>) (p, c) -> p.verifyDevcontainerCli(c, "0.87.0"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateMethods")
    void templateMethod_containerPlot_isRefusedOnTheCallingThread(
            String name, BiConsumer<GroveProvider, ContainerPlot> call) {
        NoHookProvider provider = new NoHookProvider(executor);
        ContainerPlot container = containerPlot();

        assertThatThrownBy(() -> call.accept(provider, container))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no-hook")
            .hasMessageContaining(container.id().toString());
    }

    @Test
    void reserve_stampsThisProviderAndMapsTheRequest() {
        NoHookProvider provider = new NoHookProvider(executor);
        PlotRequest sentinel = new PlotRequest("sentinel-type", 3, 5000, 77, "sentinel-serial");
        UUID groveId = UUID.randomUUID();

        Plot reserved = provider.reserve(groveId, sentinel);

        assertThat(reserved).isInstanceOf(VmPlot.class);
        VmPlot vm = (VmPlot) reserved;
        assertThat(vm.providerId()).isEqualTo("no-hook");
        assertThat(vm.state()).isEqualTo(PlotState.GERMINATING);
        assertThat(vm.spec()).isEqualTo(new VmSpec(3, 5000, 77, "sentinel-type", "sentinel-serial"));
        assertThat(vm.groveId()).isEqualTo(groveId);
        assertThat(vm.authorizedKeys()).isEmpty();
        assertThat(vm.host()).isNull();
        assertThat(vm.providerInstanceId()).isNull();

        Plot second = provider.reserve(groveId, sentinel);

        assertThat(vm.id()).isNotNull();
        assertThat(second.id()).isNotNull().isNotEqualTo(vm.id());
    }
}
