package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class VineTest {

    private static final class StubRunner implements CommandRunner {
        @Override public String execute(String command) { return "ok"; }
        @Override public String execute(String command, long timeoutSeconds) { return "ok"; }
        @Override public void executeStreaming(String c, Consumer<String> l, long t) { l.accept("ok"); }
        @Override public Optional<String> readFile(String remotePath) { return Optional.empty(); }
    }

    @Test
    void vine_exposesItsCommandRunner() throws Exception {
        CommandRunner runner = new StubRunner();
        Vine vine = () -> runner;

        assertThat(vine.commands()).isSameAs(runner);
        assertThat(vine.commands().execute("echo hi")).isEqualTo("ok");
    }
}
