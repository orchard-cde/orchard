package dev.orchard.nursery.container;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.ProviderType;
import dev.orchard.core.model.SeedlingSpec;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ContainerVine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ContainerGroveProviderTest {

    @TempDir
    Path root;

    private ScriptedRunner local;
    private ContainerGroveProvider provider;

    @BeforeEach
    void setUp() {
        local = new ScriptedRunner();
        provider = new ContainerGroveProvider(new ContainerConfig(root), mock(FruitGrower.class), local);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private static SeedlingSpec spec() {
        return new SeedlingSpec("n/a", 2, 2048, 20, null);
    }

    private ContainerPlot draft() {
        return (ContainerPlot) provider.reserve(UUID.randomUUID(), spec());
    }

    private ContainerPlot planted(PlotState state) {
        ContainerPlot d = draft();
        return new ContainerPlot(d.id(), d.groveId(), d.providerId(), d.id().toString(), state,
            Instant.now(), Instant.now(), d.workspacePath(), ContainerRuntime.DOCKER, d.spec(), null);
    }

    @Test
    void draft_fields() {
        ContainerPlot plot = draft();

        assertThat(plot.state()).isEqualTo(PlotState.GERMINATING);
        assertThat(plot.runtime()).isNull();
        assertThat(plot.spec().cpuCores()).isEqualTo(2);
        assertThat(plot.spec().memoryMb()).isEqualTo(2048);
        assertThat(plot.providerId()).isEqualTo("container");
    }

    @Test
    void draft_workspaceDirNamedAfterPlot() {
        ContainerPlot plot = draft();

        assertThat(plot.workspacePath())
            .isEqualTo(root.resolve(plot.id().toString()).resolve(plot.id().toString()));
    }

    @Test
    void twoPlots_distinctComposeProjectBasenames() {
        assertThat(draft().workspacePath().getFileName()).isNotEqualTo(draft().workspacePath().getFileName());
    }

    @Test
    void launch_dockerOk_isDocker() throws Exception {
        local.on(c -> c.equals("docker info"), "ok");

        Plot planted = provider.plantSubstrate(draft()).get();

        assertThat(planted.state()).isEqualTo(PlotState.ROOTED);
        assertThat(((ContainerPlot) planted).runtime()).isEqualTo(ContainerRuntime.DOCKER);
        assertThat(local.commands).noneMatch(c -> c.startsWith("podman"));
    }

    @Test
    void launch_realDocker_isDocker() throws Exception {
        local.on(c -> c.startsWith("docker version"), "null");

        ContainerPlot planted = (ContainerPlot) provider.plantSubstrate(draft()).get();

        assertThat(planted.runtime()).isEqualTo(ContainerRuntime.DOCKER);
        assertThat(local.commands).noneMatch(c -> c.startsWith("podman"));
    }

    @Test
    void launch_dockerCliOverPodman_isPodman() throws Exception {
        local.on(c -> c.startsWith("docker version"), "[{\"Name\":\"Podman Engine\"}]");

        ContainerPlot planted = (ContainerPlot) provider.plantSubstrate(draft()).get();

        assertThat(planted.state()).isEqualTo(PlotState.ROOTED);
        assertThat(planted.runtime()).isEqualTo(ContainerRuntime.PODMAN);
    }

    @Test
    void launch_dockerCliOverPodman_podmanCliMissing_isDocker() throws Exception {
        local.on(c -> c.startsWith("docker version"), "[{\"Name\":\"Podman Engine\"}]");
        local.fail(c -> c.equals("podman info"));

        ContainerPlot planted = (ContainerPlot) provider.plantSubstrate(draft()).get();

        assertThat(planted.runtime()).isEqualTo(ContainerRuntime.DOCKER);
    }

    @Test
    void launch_dockerFails_podmanOk_isPodman() throws Exception {
        local.fail(c -> c.equals("docker info"));
        local.on(c -> c.equals("podman info"), "ok");

        ContainerPlot planted = (ContainerPlot) provider.plantSubstrate(draft()).get();

        assertThat(planted.state()).isEqualTo(PlotState.ROOTED);
        assertThat(planted.runtime()).isEqualTo(ContainerRuntime.PODMAN);
    }

    @Test
    void launch_bothFail_blighted() throws Exception {
        local.fail(c -> c.endsWith(" info"));

        Plot planted = provider.plantSubstrate(draft()).get();

        assertThat(planted.state()).isEqualTo(PlotState.BLIGHTED);
        assertThat(local.commands).noneMatch(c -> c.startsWith("mkdir"));
    }

    @Test
    void launch_workspaceRootWithSpace_quotesMkdir() throws Exception {
        Path spaced = root.resolve("with space");
        provider.close();
        provider = new ContainerGroveProvider(new ContainerConfig(spaced), mock(FruitGrower.class), local);
        ContainerPlot plot = draft();

        provider.plantSubstrate(plot).get();

        assertThat(local.commands).contains("mkdir -p '" + plot.workspacePath() + "'");
    }

    @Test
    void ready_setsRuntimeAndHandle() throws Exception {
        ContainerPlot plot = draft();

        ContainerPlot ready = (ContainerPlot) provider.plantSubstrate(plot).get();

        assertThat(ready.runtime()).isEqualTo(ContainerRuntime.DOCKER);
        assertThat(ready.providerInstanceId()).isEqualTo(plot.id().toString());
    }

    @Test
    void water_notDormant_throwsOnCallingThread() {
        assertThatThrownBy(() -> provider.water(planted(PlotState.ROOTED)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void water_dormant_isRooted() throws Exception {
        assertThat(provider.water(planted(PlotState.DORMANT)).get().state()).isEqualTo(PlotState.ROOTED);
    }

    @Test
    void dormant_isDormant() throws Exception {
        assertThat(provider.dormant(planted(PlotState.ROOTED)).get().state()).isEqualTo(PlotState.DORMANT);
    }

    @Test
    void inspect_missingDir_withered() throws Exception {
        local.on(c -> c.contains("[ -d "), "missing");

        assertThat(provider.inspect(planted(PlotState.ROOTED)).get().state()).isEqualTo(PlotState.WITHERED);
        assertThat(local.commands).noneMatch(c -> c.startsWith("docker ps"));
    }

    @Test
    void inspect_runningContainer_rooted() throws Exception {
        ContainerPlot plot = planted(PlotState.DORMANT);
        local.on(c -> c.contains("[ -d "), "present");
        local.on(c -> c.startsWith("docker ps -q"), "abc123\n");

        assertThat(provider.inspect(plot).get().state()).isEqualTo(PlotState.ROOTED);
        assertThat(local.commands).contains("docker ps -q --filter 'label=orchard.plot.id=" + plot.id()
            + "' --filter status=running");
    }

    @Test
    void inspect_stoppedContainer_isDormant() throws Exception {
        local.on(c -> c.contains("[ -d "), "present");
        local.on(c -> c.startsWith("docker ps -q"), "");

        assertThat(provider.inspect(planted(PlotState.ROOTED)).get().state()).isEqualTo(PlotState.DORMANT);
    }

    @Test
    void uproot_onlyRemovesThisPlotsContainers() throws Exception {
        ContainerPlot plot = planted(PlotState.DORMANT);
        local.on(c -> c.startsWith("docker ps -aq"), "c1\nc2\n");

        provider.uproot(plot).get();

        assertThat(local.commands).containsExactly(
            "docker ps -aq --filter 'label=orchard.plot.id=" + plot.id() + "'",
            "docker rm -f 'c1'",
            "docker rm -f 'c2'",
            "rm -rf '" + root.resolve(plot.id().toString()) + "'");
    }

    @Test
    void uproot_engineDown_completesExceptionally() {
        local.fail(c -> c.startsWith("docker ps"));

        assertThatThrownBy(() -> provider.uproot(planted(PlotState.DORMANT)).get())
            .isInstanceOf(ExecutionException.class);
        assertThat(local.commands).noneMatch(c -> c.startsWith("rm -rf"));
    }

    @Test
    void uproot_nullRuntime_skipsContainersAndRemovesDir() throws Exception {
        ContainerPlot d = draft();

        provider.uproot(d).get();

        assertThat(local.commands).containsExactly("rm -rf '" + root.resolve(d.id().toString()) + "'");
    }

    @Test
    void uproot_usesPersistedPathNotCurrentConfig() throws Exception {
        Path otherRoot = root.resolve("other");
        ContainerPlot d = draft();
        ContainerPlot plot = new ContainerPlot(d.id(), d.groveId(), d.providerId(), d.id().toString(),
            PlotState.DORMANT, Instant.now(), Instant.now(),
            otherRoot.resolve(d.id().toString()).resolve(d.id().toString()), ContainerRuntime.DOCKER, d.spec(), null);

        provider.uproot(plot).get();

        assertThat(local.commands).contains("rm -rf '" + otherRoot.resolve(d.id().toString()) + "'");
        assertThat(local.commands).noneMatch(c -> c.startsWith("rm -rf") && c.contains(root + "/" + d.id()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/workspace", "/tmp/x/workspace", "/tmp/x/ID", "/tmp/ID/workspace", "/tmp/OTHER/ID",
        "/tmp/ID/OTHER", "REL"})
    void uproot_unexpectedPath_refusesAndDeletesNothing(String path) {
        ContainerPlot d = draft();
        String id = d.id().toString();
        String workspace = path.equals("REL") ? "rel/" + id + "/" + id
            : path.replace("OTHER", UUID.randomUUID().toString()).replace("ID", id);
        ContainerPlot plot = new ContainerPlot(d.id(), d.groveId(), d.providerId(), d.id().toString(),
            PlotState.DORMANT, Instant.now(), Instant.now(), Path.of(workspace), ContainerRuntime.DOCKER, d.spec(), null);

        assertThatThrownBy(() -> provider.uproot(plot).get())
            .isInstanceOf(ExecutionException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refusing to delete unexpected plot directory");
        assertThat(local.commands).noneMatch(c -> c.startsWith("rm -rf"));
    }

    @Test
    void uproot_rmFails_completesExceptionally() {
        local.on(c -> c.startsWith("docker ps -aq"), "c1\n");
        local.fail(c -> c.startsWith("docker rm -f"));

        assertThatThrownBy(() -> provider.uproot(planted(PlotState.DORMANT)).get())
            .isInstanceOf(ExecutionException.class);
        assertThat(local.commands).noneMatch(c -> c.startsWith("rm -rf"));
    }

    @Test
    void uproot_rmRfFails_completesExceptionally() {
        local.fail(c -> c.startsWith("rm -rf"));

        assertThatThrownBy(() -> provider.uproot(planted(PlotState.DORMANT)).get())
            .isInstanceOf(ExecutionException.class);
    }

    @Test
    void isAvailable_neverThrows() {
        local.throwRuntime = true;
        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_dockerOk_true() {
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void vine_isContainerVine() {
        assertThat(provider.vine(planted(PlotState.ROOTED))).isInstanceOf(ContainerVine.class);
    }

    @Test
    void host_isLocalRunner() {
        assertThat(provider.host(planted(PlotState.ROOTED))).isSameAs(local);
    }

    @Test
    void type_isContainer() {
        assertThat(provider.type()).isEqualTo(ProviderType.CONTAINER);
        assertThat(provider.getProviderId()).isEqualTo("container");
    }

    private static final class ScriptedRunner implements CommandRunner {
        final List<String> commands = new ArrayList<>();
        private final Map<Predicate<String>, String> outputs = new LinkedHashMap<>();
        private final List<Predicate<String>> failures = new ArrayList<>();
        boolean throwRuntime;

        void on(Predicate<String> match, String output) {
            outputs.put(match, output);
        }

        void fail(Predicate<String> match) {
            failures.add(match);
        }

        @Override
        public synchronized String execute(String command) throws IOException {
            if (throwRuntime) {
                throw new IllegalStateException("boom");
            }
            commands.add(command);
            if (failures.stream().anyMatch(f -> f.test(command))) {
                throw new IOException("exit 1: " + command);
            }
            return outputs.entrySet().stream().filter(e -> e.getKey().test(command))
                .map(Map.Entry::getValue).findFirst().orElse("");
        }

        @Override
        public String execute(String command, long timeoutSeconds) throws IOException {
            return execute(command);
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> readFile(String remotePath) {
            return Optional.empty();
        }
    }
}
