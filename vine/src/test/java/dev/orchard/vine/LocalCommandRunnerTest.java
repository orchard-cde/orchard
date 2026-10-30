package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalCommandRunnerTest {

    private final LocalCommandRunner runner = new LocalCommandRunner();

    @Test
    void execute_returnsStdout() throws Exception {
        assertThat(runner.execute("echo hi")).isEqualTo("hi\n");
    }

    @Test
    void execute_nonZero_throwsWithStderr() {
        assertThatThrownBy(() -> runner.execute("echo boom >&2; exit 3"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("boom")
            .hasMessageContaining("3");
    }

    @Test
    void execute_timeout_throws() {
        assertThatThrownBy(() -> runner.execute("sleep 5", 1))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("timed out");
    }

    @Test
    void executeStreaming_deliversLinesInOrder() throws Exception {
        List<String> lines = new ArrayList<>();
        runner.executeStreaming("printf 'a\\nb\\nc\\n'", lines::add, 10);
        assertThat(lines).containsExactly("a", "b", "c");
    }

    @Test
    void executeStreaming_largeStderr_doesNotDeadlock() throws Exception {
        List<String> lines = new ArrayList<>();
        runner.executeStreaming("head -c 1048576 /dev/zero | tr '\\0' 'x' | fold -w 80 >&2; echo done", lines::add, 20);
        assertThat(lines).containsExactly("done");
    }

    @Test
    void readFile_missing_isEmpty() {
        assertThat(runner.readFile("/nonexistent/orchard-no-such-file")).isEmpty();
    }
}
