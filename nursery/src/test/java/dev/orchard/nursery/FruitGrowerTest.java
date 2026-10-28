package dev.orchard.nursery;

import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import dev.orchard.core.model.WaitFor;
import dev.orchard.nursery.event.FruitProgressEvent;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ExecTarget;
import dev.orchard.vine.SshVine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FruitGrower}.
 *
 * <p>The CLI path is tested by mocking {@link DevcontainerCli} directly. The legacy docker path
 * is tested via the feature flag — its behaviour mirrors the pre-#74 grow flow and is exercised
 * end-to-end by {@code FruitGrowerIT}; we only assert here that the legacy code path is taken
 * when the flag is off (CLI mock is never invoked) and that the {@code parsePortOutput} helpers
 * still round-trip correctly.
 */
class FruitGrowerTest {

    private static final UUID GROVE_ID = UUID.randomUUID();
    private static final UUID SEEDLING_ID = UUID.randomUUID();
    private static final String WORKSPACE_PATH = "/workspace";


    @Test
    void parsePortOutput_standardTcpMapping() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:8080");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_multipleMappings() {
        String output = "8080/tcp -> 0.0.0.0:8080\n3000/tcp -> 0.0.0.0:3000";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
        assertThat(mappings.get(1)).isEqualTo(new Fruit.PortMapping(3000, 3000, "tcp"));
    }

    @Test
    void parsePortOutput_skipsIpv6Lines() {
        String output = "8080/tcp -> 0.0.0.0:8080\n8080/tcp -> [::]:8080";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst()).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_differentHostPort() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:49152");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 49152, "tcp"));
    }

    @Test
    void parsePortOutput_emptyOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_noArrowOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("no ports");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_udpProtocol() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("53/udp -> 0.0.0.0:53");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(53, 53, "udp"));
    }


    @Test
    void growViaCli_happyPath_returnsRipeFruit() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());

        when(cli.up(any(), eq(WORKSPACE_PATH), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c123", null, "vscode", WORKSPACE_PATH));
        when(cli.inspectContainerName(any(), eq("c123"))).thenReturn("real-container-name");

        FruitGrower grower = new FruitGrower(cli, true, events);

        Fruit result = grower.grow(execTarget(runner), fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        assertThat(result.containerId()).isEqualTo("c123");
        assertThat(result.containerName()).isEqualTo("real-container-name");
    }

    @Test
    void growViaCli_capturesComposeProjectName() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Fruit withProject = buddedFruit(defaultSeed());
        Fruit withoutProject = buddedFruit(defaultSeed());

        when(cli.up(any(), eq(WORKSPACE_PATH), eq(withProject.id()), any(), any()))
            .thenReturn(new DevcontainerCliResult("c1", "proj", "vscode", WORKSPACE_PATH));
        when(cli.up(any(), eq(WORKSPACE_PATH), eq(withoutProject.id()), any(), any()))
            .thenReturn(new DevcontainerCliResult("c2", null, "vscode", WORKSPACE_PATH));
        when(cli.inspectContainerName(any(), any())).thenReturn("real-name");

        FruitGrower grower = new FruitGrower(cli, true, mock(ApplicationEventPublisher.class));

        assertThat(grower.grow(execTarget(runner), withProject).get().composeProjectName()).isEqualTo("proj");
        assertThat(grower.grow(execTarget(runner), withoutProject).get().composeProjectName()).isNull();
    }

    @Test
    void growViaCli_cliError_returnsRottedFruit() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());

        CliError error = new CliError("boom", "feature install failed", "ghcr.io/devcontainers/features/bad:1",
            null, false, "https://example/docs");
        doThrow(new DevcontainerCli.DevcontainerCliException(error))
            .when(cli).up(any(), any(), any(), anyString(), any());

        FruitGrower grower = new FruitGrower(cli, true, null);

        Fruit result = grower.grow(execTarget(runner), fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.ROTTED);
        // Inspect should not be called once the up() throws.
        verify(cli, never()).inspectContainerName(any(), anyString());
    }

    @Test
    void growViaCli_postAttachWaitFor_staysBuddedUntilAttach() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Seed seed = Seed.devcontainer()
            .name("post-attach-seed")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .waitFor(WaitFor.POST_ATTACH_COMMAND)
            .postAttachCommand(new LifecycleCommand.Sequential(List.of("echo", "hi")))
            .build();
        Fruit fruit = buddedFruit(seed);

        when(cli.up(any(), eq(WORKSPACE_PATH), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-pa", null, "vscode", WORKSPACE_PATH));
        when(cli.inspectContainerName(any(), eq("c-pa"))).thenReturn(fruit.containerName());

        FruitGrower grower = new FruitGrower(cli, true, null);

        // grow() must NOT flip to RIPE when waitFor=POST_ATTACH_COMMAND.
        Fruit afterGrow = grower.grow(execTarget(runner), fruit).get();
        assertThat(afterGrow.state()).isEqualTo(FruitState.BUDDING);
        assertThat(afterGrow.containerId()).isEqualTo("c-pa");

        // attach() runs the post-attach command via CLI exec and flips to RIPE.
        Fruit afterAttach = grower.attach(execTarget(runner), afterGrow).get();
        verify(cli).exec(same(runner), eq(WORKSPACE_PATH), eq("echo hi"));
        assertThat(afterAttach.state()).isEqualTo(FruitState.RIPE);
    }

    @Test
    void growViaCli_publishesPhaseTransitionEvents() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());

        // Simulate the CLI streaming three JSON lines: a build start, a feature install, and
        // the terminal outcome=success. The grower's PhaseTransitionFilter should publish on
        // each distinct phase boundary.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = inv.getArgument(4);
            sink.accept("{\"type\":\"progress\",\"message\":\"Building image abc\"}");
            sink.accept("{\"type\":\"progress\",\"message\":\"Running install.sh for ghcr.io/.../node:1\"}");
            sink.accept("{\"outcome\":\"success\",\"containerId\":\"c-events\"}");
            return new DevcontainerCliResult("c-events", null, "vscode", WORKSPACE_PATH);
        }).when(cli).up(any(), any(), any(), anyString(), any());

        when(cli.inspectContainerName(any(), eq("c-events"))).thenReturn(fruit.containerName());

        FruitGrower grower = new FruitGrower(cli, true, events);

        Fruit result = grower.grow(execTarget(runner), fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        List<FruitProgressEvent> published = captor.getAllValues().stream()
            .filter(FruitProgressEvent.class::isInstance)
            .map(FruitProgressEvent.class::cast)
            .toList();
        assertThat(published).isNotEmpty();
        assertThat(published).extracting(FruitProgressEvent::phase)
            .containsExactly("BUILD_START", "FEATURE_INSTALL_START", "READY");
        assertThat(published.getFirst().fruitId()).isEqualTo(fruit.id());
        assertThat(published.getFirst().groveId()).isEqualTo(fruit.groveId());
    }

    @Test
    void growExecutesAgainstTheSuppliedWorkspacePath() throws Exception {
        // Regression coverage for the FruitGrower/CommandRunner decoupling (#86): the CLI must
        // receive whatever workspace path grow() was called with, not a hardcoded "/workspace".
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());
        String customWorkspacePath = "/custom/workspace";

        when(cli.up(any(), eq(customWorkspacePath), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-custom", null, "vscode", customWorkspacePath));
        when(cli.inspectContainerName(any(), eq("c-custom"))).thenReturn(fruit.containerName());

        FruitGrower grower = new FruitGrower(cli, true, null);

        Fruit result = grower.grow(new ExecTarget(runner, customWorkspacePath, SEEDLING_ID), fruit).get();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        verify(cli).up(any(), eq(customWorkspacePath), eq(fruit.id()), eq(fruit.containerName()), any());
        verify(cli, never()).up(any(), eq(WORKSPACE_PATH), any(), anyString(), any());
    }

    @Test
    void growViaDocker_featureFlagOff_usesLegacyPath() throws Exception {
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());

        // Feature flag off — even though a CLI mock is wired, grow() must route to the legacy
        // path. We can't run docker over SSH from a unit test, so the runner's bogus target makes
        // executeSsh() fail; the grower's catch-all maps that to ROTTED. The critical assertion
        // is that the CLI mock was never touched.
        FruitGrower grower = new FruitGrower(cli, false, null);

        Fruit result = grower.grow(execTarget(runner), fruit).get();

        verify(cli, never()).up(any(), any(), any(), anyString(), any());
        verify(cli, never()).inspectContainerName(any(), anyString());
        // The legacy path's SSH call will fail (no real VM), so the fruit rots — that's the
        // signal we took the legacy branch.
        assertThat(result.state()).isEqualTo(FruitState.ROTTED);
    }

    @Test
    void containerNameRegression_isPreservedAfterGrow() throws Exception {
        // Regression: the CLI may name the actual container
        // differently from Fruit.containerName (e.g. on regrow). FruitGrower MUST call
        // inspectContainerName and overwrite Fruit.containerName with the real value.
        DevcontainerCli cli = mock(DevcontainerCli.class);
        CommandRunner runner = runner();
        Fruit fruit = buddedFruit(defaultSeed());
        String originalContainerName = fruit.containerName();

        when(cli.up(any(), eq(WORKSPACE_PATH), eq(fruit.id()), eq(fruit.containerName()), any()))
            .thenReturn(new DevcontainerCliResult("c-real", null, "vscode", WORKSPACE_PATH));
        when(cli.inspectContainerName(any(), eq("c-real"))).thenReturn("real-container-name");

        FruitGrower grower = new FruitGrower(cli, true, null);

        Fruit result = grower.grow(execTarget(runner), fruit).get();

        assertThat(result.containerName())
            .as("Fruit.containerName must reflect the actual host name returned by docker inspect")
            .isEqualTo("real-container-name")
            .isNotEqualTo(originalContainerName);
        assertThat(result.containerId()).isEqualTo("c-real");
        verify(cli).inspectContainerName(same(runner), eq("c-real"));
    }


    private static final LifecycleCommand POST_START = new LifecycleCommand.Sequential(List.of("echo", "up"));

    private static FruitGrower plainGrower() {
        return new FruitGrower(mock(DevcontainerCli.class), false, mock(ApplicationEventPublisher.class));
    }

    private static Fruit runningFruit(Seed seed) {
        return buddedFruit(seed).withContainerDetails("cid1", List.of());
    }

    private static Seed seedWithPostStart() {
        return Seed.devcontainer()
            .name("orchard-fruit")
            .image("img")
            .postStartCommand(POST_START)
            .build();
    }

    @Test
    void pick_singleContainer_runsDockerStop() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);

        Fruit result = plainGrower().pick(execTarget(runner), runningFruit(defaultSeed())).join();

        assertThat(result.state()).isEqualTo(FruitState.PICKED);
        verify(runner).execute("docker stop cid1");
    }

    @Test
    void pick_compose_stopsWholeProject() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        Fruit fruit = runningFruit(defaultSeed()).withComposeProjectName("proj");

        Fruit result = plainGrower().pick(execTarget(runner), fruit).join();

        assertThat(result.state()).isEqualTo(FruitState.PICKED);
        verify(runner).execute("docker compose -p 'proj' stop");
        verify(runner, never()).execute("docker stop cid1");
    }

    @Test
    void pick_commandFails_completesExceptionally() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute(anyString())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> plainGrower().pick(execTarget(runner), runningFruit(defaultSeed())).join())
            .isInstanceOf(CompletionException.class);
    }

    @Test
    void ripen_singleContainer_runsDockerStart_thenPostStart() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);

        Fruit result = plainGrower().ripen(execTarget(runner), runningFruit(seedWithPostStart())).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        var order = inOrder(runner);
        order.verify(runner).execute("docker start cid1");
        order.verify(runner).execute("docker exec cid1 /bin/sh -c 'echo up'");
        order.verifyNoMoreInteractions();
    }

    @Test
    void ripen_devfileSeed_runsPostStart() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        Seed seed = DevfileSeed.builder().name("n").image("img").postStartCommand(POST_START).build();

        Fruit result = plainGrower().ripen(execTarget(runner), runningFruit(seed)).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        var order = inOrder(runner);
        order.verify(runner).execute("docker start cid1");
        order.verify(runner).execute("docker exec cid1 /bin/sh -c 'echo up'");
        order.verifyNoMoreInteractions();
    }

    @Test
    void ripen_compose_startsWholeProject() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        Fruit fruit = runningFruit(seedWithPostStart()).withComposeProjectName("proj");

        Fruit result = plainGrower().ripen(execTarget(runner), fruit).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        var order = inOrder(runner);
        order.verify(runner).execute("docker compose -p 'proj' start");
        order.verify(runner).execute("docker exec cid1 /bin/sh -c 'echo up'");
        order.verifyNoMoreInteractions();
    }

    @Test
    void ripen_noPostStart_runsOnlyStart() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);

        plainGrower().ripen(execTarget(runner), runningFruit(defaultSeed())).join();

        verify(runner).execute("docker start cid1");
        org.mockito.Mockito.verifyNoMoreInteractions(runner);
    }

    @Test
    void ripen_startOk_postStartFails_stillRipe_logsWarning() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute("docker exec cid1 /bin/sh -c 'echo up'")).thenThrow(new IOException("exit 128"));
        Fruit fruit = runningFruit(seedWithPostStart());

        java.io.PrintStream originalErr = System.err;
        var captured = new java.io.ByteArrayOutputStream();
        Fruit result;
        try {
            System.setErr(new java.io.PrintStream(captured, true));
            result = plainGrower().ripen(execTarget(runner), fruit).join();
        } finally {
            System.setErr(originalErr);
        }

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        verify(runner).execute("docker start cid1");
        assertThat(captured.toString())
            .contains("WARN")
            .contains("postStartCommand")
            .contains(fruit.id().toString());
    }

    @Test
    void ripen_containerStartFails_completesExceptionally_withoutPostStart() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute("docker start cid1")).thenThrow(new IOException("no such container"));

        assertThatThrownBy(() -> plainGrower().ripen(execTarget(runner), runningFruit(seedWithPostStart())).join())
            .isInstanceOf(CompletionException.class);
        verify(runner, never()).execute("docker exec cid1 /bin/sh -c 'echo up'");
    }

    @Test
    void ripen_cliGrownDevcontainer_runsUserCommandsViaCli() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute(org.mockito.ArgumentMatchers.startsWith("devcontainer run-user-commands"),
                org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn("{\"outcome\":\"success\"}\n");
        FruitGrower grower = new FruitGrower(
            new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 600, 60)), true, null);

        Fruit result = grower.ripen(execTarget(runner), runningFruit(seedWithPostStart())).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        var order = inOrder(runner);
        order.verify(runner).execute("docker start cid1");
        order.verify(runner).execute(
            "devcontainer run-user-commands --workspace-folder " + WORKSPACE_PATH
                + " --container-id cid1 --log-format json --skip-post-attach",
            600L);
        order.verifyNoMoreInteractions();
    }

    @Test
    void ripen_cliGrownDevcontainer_userCommandsFail_stillRipe() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute(org.mockito.ArgumentMatchers.startsWith("devcontainer run-user-commands"),
                org.mockito.ArgumentMatchers.anyLong()))
            .thenThrow(new IOException("exit 1"));
        FruitGrower grower = new FruitGrower(
            new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 600, 60)), true, null);

        Fruit result = grower.ripen(execTarget(runner), runningFruit(seedWithPostStart())).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
    }

    @Test
    void ripen_devfileSeed_cliEnabled_stillRunsPostStartViaDockerExec() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        DevcontainerCli cli = mock(DevcontainerCli.class);
        Seed seed = DevfileSeed.builder().name("n").image("img").postStartCommand(POST_START).build();

        Fruit result = new FruitGrower(cli, true, null).ripen(execTarget(runner), runningFruit(seed)).join();

        assertThat(result.state()).isEqualTo(FruitState.RIPE);
        var order = inOrder(runner);
        order.verify(runner).execute("docker start cid1");
        order.verify(runner).execute("docker exec cid1 /bin/sh -c 'echo up'");
        order.verifyNoMoreInteractions();
        org.mockito.Mockito.verifyNoInteractions(cli);
    }

    @Test
    void ripen_commandFails_completesExceptionally() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.execute(anyString())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> plainGrower().ripen(execTarget(runner), runningFruit(defaultSeed())).join())
            .isInstanceOf(CompletionException.class);
    }


    private static Seed defaultSeed() {
        return Seed.devcontainer()
            .name("orchard-fruit")
            .image("mcr.microsoft.com/devcontainers/base:ubuntu")
            .build();
    }

    private static Fruit buddedFruit(Seed seed) {
        return Fruit.bud(GROVE_ID, SEEDLING_ID, seed);
    }

    private static CommandRunner runner() {
        // 127.0.0.255 / port 1 — guaranteed to refuse on any host the test runs on.
        return new SshVine(new VmPlot(SEEDLING_ID, UUID.randomUUID(), "test", null, PlotState.ROOTED, Instant.now(), null, "127.0.0.255", 1, List.of(), VmSpec.small())).commands();
    }

    private static ExecTarget execTarget(CommandRunner runner) {
        return new ExecTarget(runner, WORKSPACE_PATH, SEEDLING_ID);
    }
}
