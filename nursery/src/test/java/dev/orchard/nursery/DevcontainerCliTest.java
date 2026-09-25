package dev.orchard.nursery;

import dev.orchard.vine.CommandRunner;
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
        public String execute(String c) { throw new UnsupportedOperationException(); }

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

    private static DevcontainerCli cliFor() {
        return new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 0, 0));
    }

    @Test
    void successOutcomeParsesContainerId() throws Exception {
        CannedRunner runner = new CannedRunner(List.of(
            "{\"type\":\"progress\",\"name\":\"Building image\"}",
            "{\"type\":\"progress\",\"name\":\"Installing features\"}",
            "{\"outcome\":\"success\",\"containerId\":\"abc123\",\"remoteUser\":\"vscode\",\"remoteWorkspaceFolder\":\"/workspace\"}"
        ), 0);

        DevcontainerCliResult result = cliFor().up(
            runner, "/workspace", UUID.randomUUID(), "my-fruit", line -> {});

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

        assertThatThrownBy(() -> cliFor().up(
                runner, "/workspace", UUID.randomUUID(), "my-fruit", line -> {}))
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

        assertThatThrownBy(() -> cliFor().up(
                runner, "/workspace", UUID.randomUUID(), "my-fruit", line -> {}))
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

        assertThatThrownBy(() -> cliFor().up(
                runner, "/workspace", UUID.randomUUID(), "my-fruit", line -> {}))
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
        cliFor().up(runner, "/workspace", UUID.randomUUID(), "my-fruit", observed::add);

        assertThat(observed).containsExactlyElementsOf(fed);
    }

    @Test
    void execTargetsTheSuppliedWorkspacePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = stubRunnerCapturing(calls);
        var cli = new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 60, 60));

        cli.exec(runner, "/srv/custom-workspace", "echo hi");

        assertThat(calls).anyMatch(c -> c.contains("--workspace-folder /srv/custom-workspace"));
    }

    @Test
    void upTargetsTheSuppliedWorkspacePath() throws Exception {
        var calls = new ArrayList<String>();
        CommandRunner runner = stubRunnerCapturing(calls);
        var cli = new DevcontainerCli(new DevcontainerCliConfig("0.87.0", 60, 60));

        cli.up(runner, "/srv/custom-workspace", UUID.randomUUID(), "my-fruit", line -> {});

        assertThat(calls).anyMatch(c -> c.contains("--workspace-folder /srv/custom-workspace"));
    }
}
