package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerLabelsTest {

    private static final UUID PLOT = UUID.fromString("00000000-0000-0000-0000-00000000abcd");

    private static final class ScriptedRunner implements CommandRunner {
        final List<String> commands = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        final Function<String, String> answers;

        ScriptedRunner(String answer) {
            this(command -> answer);
        }

        ScriptedRunner(Function<String, String> answers) {
            this.answers = answers;
        }

        @Override
        public String execute(String command) {
            throw new UnsupportedOperationException("containerIds must pass a timeout");
        }

        @Override
        public String execute(String command, long timeoutSeconds) {
            commands.add(command);
            timeouts.add(timeoutSeconds);
            return answers.apply(command);
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

    @Test
    void label_and_filter() {
        assertThat(ContainerLabels.PLOT_ID).isEqualTo("orchard.plot.id");
        assertThat(ContainerLabels.label(PLOT)).isEqualTo("orchard.plot.id=" + PLOT);
        assertThat(ContainerLabels.filter(PLOT)).isEqualTo("label=orchard.plot.id=" + PLOT);
    }

    @Test
    void containerIds_running_trimsLinesAndDropsBlanks() throws Exception {
        ScriptedRunner runner = new ScriptedRunner("  abc \n\n def\n   \n");

        assertThat(ContainerLabels.containerIds(runner, "docker", PLOT, false, 7)).containsExactly("abc", "def");
        assertThat(runner.commands).containsExactly("docker ps -q --filter 'label=orchard.plot.id=" + PLOT + "'");
        assertThat(runner.timeouts).containsExactly(7L);
    }

    @Test
    void containerIds_includeStopped_addsA() throws Exception {
        ScriptedRunner runner = new ScriptedRunner("c1\n");

        assertThat(ContainerLabels.containerIds(runner, "podman", PLOT, true, 9)).containsExactly("c1");
        assertThat(runner.commands).containsExactly("podman ps -aq --filter 'label=orchard.plot.id=" + PLOT + "'");
        assertThat(runner.timeouts).containsExactly(9L);
    }

    @Test
    void containerIds_emptyOutput_isEmpty() throws Exception {
        assertThat(ContainerLabels.containerIds(new ScriptedRunner(""), "docker", PLOT, false, 60)).isEmpty();
        assertThat(ContainerLabels.containerIds(new ScriptedRunner("\n \n"), "docker", PLOT, false, 60)).isEmpty();
    }

    @Test
    void composeContainerIds_filtersByProjectLabel() throws Exception {
        ScriptedRunner runner = new ScriptedRunner("c1\nc2\n");

        assertThat(ContainerLabels.composeContainerIds(runner, "podman", "my proj", false, 5))
            .containsExactly("c1", "c2");
        assertThat(runner.commands).containsExactly("podman ps -q --filter 'label=com.docker.compose.project=my proj'");
    }

    @Test
    void removeComposeProject_removesContainersNetworksAndVolumesByLabel() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(c -> c.contains(" ps -aq ") ? "c1\nc2\n"
            : c.contains(" network ls ") ? "n1\n" : c.contains(" volume ls ") ? "v1\nv2\n" : "");

        ContainerLabels.removeComposeProject(runner, "docker", "proj", 11);

        String filter = "'label=com.docker.compose.project=proj'";
        assertThat(runner.commands).containsExactly(
            "docker ps -aq --filter " + filter,
            "docker rm -f 'c1' 'c2'",
            "docker network ls -q --filter " + filter,
            "docker network rm 'n1'",
            "docker volume ls -q --filter " + filter,
            "docker volume rm -f 'v1' 'v2'");
        assertThat(runner.timeouts).containsOnly(11L);
    }

    @Test
    void removeComposeProject_nothingLeft_onlyLists() throws Exception {
        ScriptedRunner runner = new ScriptedRunner("");

        ContainerLabels.removeComposeProject(runner, "podman", "proj", 11);

        String filter = "'label=com.docker.compose.project=proj'";
        assertThat(runner.commands).containsExactly(
            "podman ps -aq --filter " + filter,
            "podman network ls -q --filter " + filter,
            "podman volume ls -q --filter " + filter);
    }
}
