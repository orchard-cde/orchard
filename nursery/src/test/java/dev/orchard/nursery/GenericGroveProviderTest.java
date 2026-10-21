package dev.orchard.nursery;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.core.model.VmPlot;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.Vine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenericGroveProviderTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    @Test
    void plantSubstrate_reachesSaplingThroughTheBase() {
        ContainerTestProvider provider = new ContainerTestProvider(executor, new FruitGrower());
        Plot draft = provider.reserve(UUID.randomUUID(), spec());

        assertThat(provider.plantSubstrate(draft).join().state()).isEqualTo(PlotState.SAPLING);
    }

    @Test
    void plantSubstrate_readyThrows_isBlightedAndLogsTheLaunchResult() {
        ContainerTestProvider provider = new ContainerTestProvider(executor, new FruitGrower());
        provider.failReady = true;
        Plot draft = provider.reserve(UUID.randomUUID(), spec());

        Plot result = provider.plantSubstrate(draft).join();

        assertThat(result.state()).isEqualTo(PlotState.BLIGHTED);
        assertThat(provider.failureContextArgument.get()).isEqualTo("container-1");
    }

    @Test
    void plantSubstrate_vmPlot_isRefusedOnTheCallingThread() {
        ContainerTestProvider provider = new ContainerTestProvider(executor, new FruitGrower());
        VmPlot vmPlot = TestPlots.fake();

        assertThatThrownBy(() -> provider.plantSubstrate(vmPlot))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("container-test")
            .hasMessageContaining(vmPlot.id().toString());
    }

    @Test
    void growFruit_usesTheSubstratesWorkspacePath() {
        FruitGrower grower = mock(FruitGrower.class);
        when(grower.grow(any(), any())).thenReturn(new CompletableFuture<>());
        ContainerTestProvider provider = new ContainerTestProvider(executor, grower);
        ContainerPlot plot = (ContainerPlot) provider.reserve(UUID.randomUUID(), spec());

        provider.growFruit(plot, TestPlots.budded(plot));

        ArgumentCaptor<ExecTarget> target = ArgumentCaptor.forClass(ExecTarget.class);
        verify(grower).grow(target.capture(), any());
        assertTargetsTheSubstrateWorkspace(target.getValue(), plot);
    }

    @Test
    void compostFruit_usesTheSubstratesWorkspacePath() {
        FruitGrower grower = mock(FruitGrower.class);
        when(grower.compost(any(), any())).thenReturn(new CompletableFuture<>());
        ContainerTestProvider provider = new ContainerTestProvider(executor, grower);
        ContainerPlot plot = (ContainerPlot) provider.reserve(UUID.randomUUID(), spec());

        provider.compostFruit(plot, TestPlots.budded(plot));

        ArgumentCaptor<ExecTarget> target = ArgumentCaptor.forClass(ExecTarget.class);
        verify(grower).compost(target.capture(), any());
        assertTargetsTheSubstrateWorkspace(target.getValue(), plot);
    }

    private static void assertTargetsTheSubstrateWorkspace(ExecTarget target, ContainerPlot plot) {
        assertThat(target.workspacePath()).isEqualTo("/tmp/orchard-ws");
        assertThat(target.targetId()).isEqualTo(plot.id());
    }

    @Test
    void reserve_returnsTheSubstratesDraft() {
        ContainerTestProvider provider = new ContainerTestProvider(executor, new FruitGrower());
        UUID groveId = UUID.randomUUID();

        Plot first = provider.reserve(groveId, spec());
        Plot second = provider.reserve(groveId, spec());

        for (Plot plot : new Plot[] {first, second}) {
            assertThat(plot).isInstanceOf(ContainerPlot.class);
            assertThat(plot.state()).isEqualTo(PlotState.GERMINATING);
            assertThat(plot.providerId()).isEqualTo("container-test");
            assertThat(plot.groveId()).isEqualTo(groveId);
            assertThat(plot.id()).isNotNull();
        }
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    void guaranteeBearingMethods_areFinal() throws Exception {
        Class<?> base = AbstractGroveProvider.class;

        assertFinal(base.getMethod("plantSubstrate", Plot.class));
        assertFinal(base.getMethod("reserve", UUID.class, SeedlingSpec.class));
        assertFinal(base.getMethod("growFruit", Plot.class, Fruit.class));
        assertFinal(base.getMethod("compostFruit", Plot.class, Fruit.class));
        assertFinal(base.getMethod("vine", Plot.class));
        assertFinal(base.getMethod("verifyDevcontainerCli", Plot.class, String.class));
        assertFinal(base.getDeclaredMethod("narrow", Plot.class));
    }

    private static void assertFinal(Method method) {
        assertThat(Modifier.isFinal(method.getModifiers())).as("%s is final", method.getName()).isTrue();
    }

    private static SeedlingSpec spec() {
        return new SeedlingSpec("small", 2, 4096, 20, null);
    }

    private static class ContainerTestProvider extends AbstractGroveProvider<ContainerPlot, String> {

        boolean failReady;
        final AtomicReference<String> failureContextArgument = new AtomicReference<>();

        ContainerTestProvider(ExecutorService executor, FruitGrower fruitGrower) {
            super(ContainerPlot.class, executor, fruitGrower);
        }

        @Override
        public ProviderType type() {
            return ProviderType.QEMU_LOCAL;
        }

        @Override
        public String getProviderId() {
            return "container-test";
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

        @Override
        protected ContainerPlot draft(UUID groveId, SeedlingSpec seedlingSpec) {
            return new ContainerPlot(UUID.randomUUID(), groveId, getProviderId(), null,
                PlotState.GERMINATING, Instant.now(), null, Path.of("/tmp/orchard-ws"),
                ContainerRuntime.PODMAN, new ContainerSpec(seedlingSpec.cpuCores(), seedlingSpec.memoryMb()));
        }

        @Override
        protected String launch(ContainerPlot plot) {
            return "container-1";
        }

        @Override
        protected ContainerPlot ready(ContainerPlot plot, String launched) {
            if (failReady) {
                throw new IllegalStateException("ready failed");
            }
            return plot;
        }

        @Override
        protected Vine vineFor(ContainerPlot plot) {
            CommandRunner runner = mock(CommandRunner.class);
            return () -> runner;
        }

        @Override
        protected String workspacePath(ContainerPlot plot) {
            return plot.workspacePath().toString();
        }

        @Override
        protected String failureContext(String launched) {
            failureContextArgument.set(launched);
            return super.failureContext(launched);
        }
    }
}
