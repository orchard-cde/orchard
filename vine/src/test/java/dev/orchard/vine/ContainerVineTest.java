package dev.orchard.vine;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.PlotState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerVineTest {

    private final UUID plotId = UUID.randomUUID();

    private ContainerPlot plot(ContainerRuntime runtime) {
        return new ContainerPlot(plotId, UUID.randomUUID(), "p", "c-1", PlotState.ROOTED, Instant.now(),
            Instant.now(), Path.of("/ws"), runtime, new ContainerSpec(1, 512), null);
    }

    private String psCommand(String cli) {
        return cli + " ps -q --filter 'label=orchard.plot.id=" + plotId + "'";
    }

    private static final class RecordingRunner extends ContainerCommandRunner {
        final List<String> commands = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        String psAnswer = "abc\n";
        IOException psFailure;

        RecordingRunner() {
            super(null);
        }

        @Override
        public String execute(String command) throws IOException {
            return execute(command, 60);
        }

        @Override
        public String execute(String command, long timeoutSeconds) throws IOException {
            commands.add(command);
            timeouts.add(timeoutSeconds);
            if (command.contains(" ps -q ")) {
                if (psFailure != null) {
                    throw psFailure;
                }
                return psAnswer;
            }
            return "out";
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds) {
            commands.add(command);
            lineConsumer.accept("line");
        }

        @Override
        public Optional<String> readFile(String remotePath) {
            return Optional.empty();
        }
    }

    @Test
    void execute_resolvesThenExecs() throws Exception {
        RecordingRunner local = new RecordingRunner();
        String out = new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().execute("ls -la");

        assertThat(out).isEqualTo("out");
        assertThat(local.commands).containsExactly(
            psCommand("docker"),
            "'docker' 'exec' 'abc' '/bin/sh' '-c' 'ls -la'");
    }

    @Test
    void execute_withRemoteUser_passesU() throws Exception {
        RecordingRunner local = new RecordingRunner();
        new ContainerVine(plot(ContainerRuntime.DOCKER).withRemoteUser("dev user"), local).commands().execute("id");

        assertThat(local.commands).containsExactly(
            psCommand("docker"),
            "'docker' 'exec' '-u' 'dev user' 'abc' '/bin/sh' '-c' 'id'");
    }

    @Test
    void execute_withoutRemoteUser_noU() throws Exception {
        RecordingRunner local = new RecordingRunner();
        new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().execute("id");

        assertThat(local.commands.get(1)).isEqualTo("'docker' 'exec' 'abc' '/bin/sh' '-c' 'id'").doesNotContain("'-u'");
    }

    @Test
    void execute_resolvesEveryCall() throws Exception {
        RecordingRunner local = new RecordingRunner();
        CommandRunner runner = new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands();
        runner.execute("a");
        runner.execute("b");

        assertThat(local.commands.stream().filter(c -> c.contains(" ps -q ")).count()).isEqualTo(2);
    }

    @Test
    void execute_noContainer_throws() {
        RecordingRunner local = new RecordingRunner();
        local.psAnswer = "\n";
        assertThatThrownBy(() -> new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().execute("x"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("no running fruit for plot " + plotId);
    }

    @Test
    void containerVine_psFails_surfacesStderr() {
        RecordingRunner local = new RecordingRunner();
        local.psFailure = new IOException("Cannot connect to the Docker daemon");
        assertThatThrownBy(() -> new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().execute("x"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Cannot connect to the Docker daemon");
    }

    @Test
    void podmanRuntime_usesPodman() throws Exception {
        RecordingRunner local = new RecordingRunner();
        new ContainerVine(plot(ContainerRuntime.PODMAN), local).commands().execute("id");

        assertThat(local.commands).containsExactly(
            psCommand("podman"),
            "'podman' 'exec' 'abc' '/bin/sh' '-c' 'id'");
    }

    @Test
    void quotesSingleQuotes() throws Exception {
        RecordingRunner local = new RecordingRunner();
        new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().execute("echo 'x'");

        assertThat(local.commands.get(1)).isEqualTo("'docker' 'exec' 'abc' '/bin/sh' '-c' 'echo '\\''x'\\'''");
    }

    @Test
    void streaming_resolvesThenExecs() throws Exception {
        RecordingRunner local = new RecordingRunner();
        List<String> lines = new ArrayList<>();
        new ContainerVine(plot(ContainerRuntime.DOCKER), local).commands().executeStreaming("tail", lines::add, 5);

        assertThat(lines).containsExactly("line");
        assertThat(local.commands.get(1)).isEqualTo("'docker' 'exec' 'abc' '/bin/sh' '-c' 'tail'");
    }

    @Test
    void readFile_catsQuotedPath_andIsEmptyOnFailure() {
        RecordingRunner local = new RecordingRunner();
        ContainerVine vine = new ContainerVine(plot(ContainerRuntime.DOCKER), local);

        assertThat(vine.commands().readFile("/etc/a b")).contains("out");
        assertThat(local.commands.get(1))
            .isEqualTo("'docker' 'exec' 'abc' '/bin/sh' '-c' " + ShellQuote.quote("cat '/etc/a b'"));

        local.psFailure = new IOException("down");
        assertThat(vine.commands().readFile("/etc/x")).isEmpty();
    }
}
