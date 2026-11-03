package dev.orchard.nursery.container;

import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.vine.CommandRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerRuntimesTest {

    private final ScriptedRunner local = new ScriptedRunner();

    @Test
    void realDocker_isDocker() throws Exception {
        local.on(c -> c.startsWith("docker version"), "null");

        assertThat(ContainerRuntimes.resolve(local)).contains(ContainerRuntime.DOCKER);
        assertThat(local.commands).noneMatch(c -> c.startsWith("podman"));
    }

    @Test
    void dockerCliOverPodman_isPodman() throws Exception {
        local.on(c -> c.startsWith("docker version"), "[{\"Name\":\"Podman Engine\"}]");

        assertThat(ContainerRuntimes.resolve(local)).contains(ContainerRuntime.PODMAN);
    }

    @Test
    void dockerCliOverPodman_podmanCliMissing_isDocker() throws Exception {
        local.on(c -> c.startsWith("docker version"), "[{\"Name\":\"Podman Engine\"}]");
        local.fail(c -> c.equals("podman info"));

        assertThat(ContainerRuntimes.resolve(local)).contains(ContainerRuntime.DOCKER);
    }

    @Test
    void dockerDown_podmanUp_isPodman() throws Exception {
        local.fail(c -> c.equals("docker info"));
        local.on(c -> c.equals("podman info"), "ok");

        assertThat(ContainerRuntimes.resolve(local)).contains(ContainerRuntime.PODMAN);
    }

    @Test
    void bothDown_isEmpty() throws Exception {
        local.fail(c -> c.endsWith(" info"));

        assertThat(ContainerRuntimes.resolve(local)).isEmpty();
    }

    private static final class ScriptedRunner implements CommandRunner {
        final List<String> commands = new ArrayList<>();
        private final Map<Predicate<String>, String> outputs = new LinkedHashMap<>();
        private final List<Predicate<String>> failures = new ArrayList<>();

        void on(Predicate<String> match, String output) {
            outputs.put(match, output);
        }

        void fail(Predicate<String> match) {
            failures.add(match);
        }

        @Override
        public String execute(String command) throws IOException {
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
