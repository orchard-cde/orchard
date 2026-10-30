package dev.orchard.nursery;

import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.vine.CommandRunner;
import dev.orchard.vine.ExecTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that {@link DevcontainerCli} correctly parses the {@code @devcontainers/cli}
 * JSON stream and surfaces every line to the raw-line consumer. Drives the CLI through
 * a {@link CannedRunner} fake — no real SSH or CLI involvement.
 */
class DevcontainerCliTest {

    /** In-process fake that replays a canned list of JSON lines to the streaming consumer. */
    static class CannedRunner implements CommandRunner {
        private final List<String> lines;
        private final int exitCode;
        CannedRunner(List<String> lines, int exitCode) { this.lines = lines; this.exitCode = exitCode; }

        @Override
        public String execute(String c) throws IOException { throw new UnsupportedOperationException(); }

        @Override
        public String execute(String c, long timeoutSeconds) { return ""; }

        @Override
        public java.util.Optional<String> readFile(String p) { return java.util.Optional.empty(); }

        @Override
        public void executeStreaming(String c, Consumer<String> consumer, long timeoutSeconds) throws IOException {
            lines.forEach(consumer);
            if (exitCode != 0) {
                throw new IOException("canned exit " + exitCode);
            }
        }
    }

    /** In-process fake that records every command it is asked to run. */
    private static CommandRunner stubRunnerCapturing(List<String> calls) {
        return new CommandRunner() {
            @Override
            public String execute(String c) {
                calls.add(c);
                return "";
            }

            @Override
            public String execute(String c, long timeoutSeconds) {
                calls.add(c);
                return "";
            }

            @Override
            public java.util.Optional<String> readFile(String p) { return java.util.Optional.empty(); }

            @Override
            public void executeStreaming(String c, Consumer<String> consumer, long timeoutSeconds) {
                calls.add(c);
                consumer.accept("{\"outcome\":\"success\",\"containerId\":\"abc123\","
                    + "\"remoteUser\":\"vscode\",\"remoteWorkspaceFolder\":\"/workspace\"}");
            }
        };
    }

    private static final UUID PLOT_ID = UUID.randomUUID();

    private static ExecTarget target(CommandRunner runner, String workspacePath) {
        return target(runner, workspacePath, ContainerRuntime.DOCKER);
    }

    private static ExecTarget target(CommandRunner runner, String workspacePath, ContainerRuntime runtime) {
        return new ExecTarget(runner, workspacePath, PLOT_ID, runtime);
    }

    private static DevcontainerCli cliFor() {
        return new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 0, 0));
    }

    @Test
    void up_podman_passesDockerPath() throws Exception {
        var calls = new ArrayList<String>();
        cliFor().up(target(stubRunnerCapturing(calls), "/ws", ContainerRuntime.PODMAN),
            UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.startsWith("devcontainer up") && c.contains(" --docker-path podman"));
    }

    @Test
    void up_docker_omitsDockerPath() throws Exception {
        var calls = new ArrayList<String>();
        cliFor().up(target(stubRunnerCapturing(calls), "/ws"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).noneMatch(c -> c.contains("--docker-path") || c.contains("--docker-compose-path"));
    }

    @Test
    void up_podman_withPodmanCompose_passesComposePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = new CannedRunner(List.of("{\"outcome\":\"success\",\"containerId\":\"abc123\"}"), 0) {
            @Override
            public String execute(String c) {
                calls.add(c);
                return "/usr/bin/podman-compose\n";
            }

            @Override
            public void executeStreaming(String c, Consumer<String> consumer, long timeoutSeconds) throws IOException {
                calls.add(c);
                super.executeStreaming(c, consumer, timeoutSeconds);
            }
        };
        cliFor().up(target(runner, "/ws", ContainerRuntime.PODMAN), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).contains("command -v podman-compose");
        assertThat(calls).anyMatch(c -> c.startsWith("devcontainer up") && c.contains(" --docker-compose-path podman-compose"));
    }

    @Test
    void up_podman_withoutPodmanCompose_omitsComposePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = new CannedRunner(List.of("{\"outcome\":\"success\",\"containerId\":\"abc123\"}"), 0) {
            @Override
            public String execute(String c) throws IOException {
                calls.add(c);
                throw new IOException("exit 1");
            }

            @Override
            public void executeStreaming(String c, Consumer<String> consumer, long timeoutSeconds) throws IOException {
                calls.add(c);
                super.executeStreaming(c, consumer, timeoutSeconds);
            }
        };
        cliFor().up(target(runner, "/ws", ContainerRuntime.PODMAN), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.startsWith("devcontainer up") && c.contains(" --docker-path podman"));
        assertThat(calls).noneMatch(c -> c.contains("--docker-compose-path"));
    }

    @Test
    void up_addsPlotIdLabel() throws Exception {
        var calls = new ArrayList<String>();
        cliFor().up(target(stubRunnerCapturing(calls), "/ws"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.contains(" --id-label 'orchard.plot.id=" + PLOT_ID + "'"));
    }

    @Test
    void up_workspaceWithSpace_isQuoted() throws Exception {
        var calls = new ArrayList<String>();
        cliFor().up(target(stubRunnerCapturing(calls), "/tmp/a b/ws"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.contains("--workspace-folder '/tmp/a b/ws' "));
    }

    @Test
    void inspectContainerName_usesRuntimeCli() throws Exception {
        var calls = new ArrayList<String>();
        cliFor().inspectContainerName(target(stubRunnerCapturing(calls), "/ws", ContainerRuntime.PODMAN), "abc123");

        assertThat(calls).singleElement().satisfies(c -> assertThat(c).startsWith("podman inspect 'abc123'"));
    }

    @Test
    void successOutcomeParsesContainerId() throws Exception {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"type\":\"progress\",\"name\":\"Building image\"}",
            "{\"type\":\"progress\",\"name\":\"Installing features\"}",
            "{\"outcome\":\"success\",\"containerId\":\"abc123\",\"remoteUser\":\"vscode\",\"remoteWorkspaceFolder\":\"/workspace\"}"
        ), 0);

        DevcontainerCliResult result = cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(result.containerId()).isEqualTo("abc123");
        assertThat(result.remoteUser()).isEqualTo("vscode");
        assertThat(result.remoteWorkspaceFolder()).isEqualTo("/workspace");
    }

    @Test
    void errorOutcomeThrowsWithCliError() {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"type\":\"progress\",\"name\":\"Building image\"}",
            "{\"outcome\":\"error\",\"message\":\"build failed\",\"description\":\"Dockerfile RUN exited 1\"}"
        ), 0);

        assertThatThrownBy(() -> cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", line -> {}))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .satisfies(t -> {
                DevcontainerCli.DevcontainerCliException ex = (DevcontainerCli.DevcontainerCliException) t;
                assertThat(ex.error().description()).isEqualTo("Dockerfile RUN exited 1");
                assertThat(ex.error().message()).isEqualTo("build failed");
                assertThat(ex.getMessage()).isEqualTo("Dockerfile RUN exited 1");
            });
    }

    @Test
    void disallowedFeaturePopulatesId() {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"outcome\":\"error\",\"disallowedFeatureId\":\"ghcr.io/devcontainers/features/java:1\",\"didStopContainer\":true,\"containerId\":\"xyz789\"}"
        ), 0);

        assertThatThrownBy(() -> cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", line -> {}))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .satisfies(t -> {
                DevcontainerCli.DevcontainerCliException ex = (DevcontainerCli.DevcontainerCliException) t;
                assertThat(ex.error().disallowedFeatureId())
                    .isEqualTo("ghcr.io/devcontainers/features/java:1");
                assertThat(ex.error().didStopContainer()).isTrue();
                assertThat(ex.error().containerId()).isEqualTo("xyz789");
            });
    }

    @Test
    void noOutcomeLineThrowsCliCrashError() {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"type\":\"progress\",\"name\":\"Building image\"}",
            "{\"type\":\"progress\",\"name\":\"Half-built\"}"
        ), 0);

        assertThatThrownBy(() -> cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", line -> {}))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .satisfies(t -> {
                DevcontainerCli.DevcontainerCliException ex = (DevcontainerCli.DevcontainerCliException) t;
                assertThat(ex.error().message()).isEqualTo("no outcome line");
            });
    }

    @Test
    void rawLineConsumerSeesEveryLine() throws Exception {
        List<String> fed = List.of(
            "{\"type\":\"progress\",\"name\":\"Building image\"}",
            "{\"type\":\"progress\",\"name\":\"Installing features\"}",
            "{\"outcome\":\"success\",\"containerId\":\"abc123\",\"remoteUser\":\"vscode\",\"remoteWorkspaceFolder\":\"/workspace\"}"
        );
        CannedRunner runner = new CannedRunner(fed, 0);

        List<String> observed = new ArrayList<>();
        cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", observed::add);

        assertThat(observed).containsExactlyElementsOf(fed);
    }

    @Test
    void execTargetsTheSuppliedWorkspacePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = stubRunnerCapturing(calls);
        var cli = new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 60, 60));

        cli.exec(target(runner, "/srv/custom-workspace"), "echo hi");

        assertThat(calls).anyMatch(c -> c.contains("--workspace-folder '/srv/custom-workspace'"));
    }

    @Test
    void upTargetsTheSuppliedWorkspacePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = stubRunnerCapturing(calls);
        var cli = new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 60, 60));

        cli.up(target(runner, "/srv/custom-workspace"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.contains("--workspace-folder '/srv/custom-workspace'"));
    }

    @Test
    void runUserCommands_targetsExistingContainerWithoutPostAttach() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = new CannedRunner(List.of(), 0) {
            @Override
            public String execute(String c, long timeoutSeconds) {
                calls.add(c);
                return "{\"outcome\":\"success\",\"result\":\"done\"}\n";
            }
        };

        cliFor().runUserCommands(target(runner, "/srv/ws"), "abc123");

        assertThat(calls).containsExactly(
            "devcontainer run-user-commands --workspace-folder '/srv/ws' --container-id 'abc123'"
                + " --log-format json --skip-post-attach");
    }

    @Test
    void runUserCommands_errorOutcome_throws() {
        CommandRunner runner = new CannedRunner(List.of(), 0) {
            @Override
            public String execute(String c, long timeoutSeconds) {
                return "{\"outcome\":\"error\",\"message\":\"postStartCommand failed\"}\n";
            }
        };

        assertThatThrownBy(() -> cliFor().runUserCommands(target(runner, "/srv/ws"), "abc123"))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .hasMessageContaining("postStartCommand failed");
    }
    @Test
    void runUserCommands_noOutcomeLine_throws() {
        CommandRunner runner = new CannedRunner(List.of(), 0) {
            @Override
            public String execute(String c, long timeoutSeconds) {
                return "{\"type\":\"progress\",\"name\":\"Running postStartCommand\"}\n";
            }
        };

        assertThatThrownBy(() -> cliFor().runUserCommands(target(runner, "/srv/ws"), "abc123"))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .satisfies(t -> {
                DevcontainerCli.DevcontainerCliException ex = (DevcontainerCli.DevcontainerCliException) t;
                assertThat(ex.error().message()).isEqualTo("no outcome line");
                assertThat(ex.error().containerId()).isEqualTo("abc123");
            });
    }

    @Test
    void runUserCommands_errorOutcomeWithoutContainerId_fillsInTargetContainer() {
        CommandRunner runner = new CannedRunner(List.of(), 0) {
            @Override
            public String execute(String c, long timeoutSeconds) {
                return "{\"outcome\":\"error\",\"message\":\"m\",\"description\":\"d\"}\n";
            }
        };

        assertThatThrownBy(() -> cliFor().runUserCommands(target(runner, "/srv/ws"), "abc123"))
            .isInstanceOf(DevcontainerCli.DevcontainerCliException.class)
            .satisfies(t -> {
                CliError error = ((DevcontainerCli.DevcontainerCliException) t).error();
                assertThat(error.containerId()).isEqualTo("abc123");
                assertThat(error.description()).isEqualTo("d");
            });
    }

    @Test
    void outcomeLinesWithUnknownFieldsStillBind() throws Exception {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"outcome\":\"success\",\"containerId\":\"abc123\",\"remoteUser\":\"vscode\","
                + "\"remoteWorkspaceFolder\":\"/workspace\",\"addedInSomeLaterCli\":{\"x\":1}}"
        ), 0);

        DevcontainerCliResult result = cliFor().up(target(runner, "/workspace"), UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(result).isEqualTo(new DevcontainerCliResult("abc123", null, "vscode", "/workspace"));
    }
}
