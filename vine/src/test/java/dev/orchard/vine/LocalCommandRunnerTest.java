package dev.orchard.vine;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
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

    private static long readPid(Path file) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.exists(file) && !Files.readString(file).isBlank()) {
                return Long.parseLong(Files.readString(file).trim());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("pid file never written");
    }

    private static void assertDies(long pid) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("process " + pid + " still alive");
    }

    @Test
    void execute_interrupted_destroysProcess(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("pid");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                runner.execute("echo $$ > " + pidFile + "; exec sleep 30", 60);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.start();
        long pid = readPid(pidFile);
        t.interrupt();
        t.join(5000);

        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertDies(pid);
    }

    @Test
    void execute_timeout_killsDescendants(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("pid");
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.execute("sleep 30 & echo $! > " + pidFile + "; wait; echo x", 1))
            .isInstanceOf(IOException.class);

        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(5);
        assertDies(readPid(pidFile));
    }

    @Test
    void execute_backgroundedGrandchild_returnsWithinGrace(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("pid");
        long start = System.nanoTime();
        try {
            String out = runner.execute("(sleep 30 & echo $! > " + pidFile + ") ; echo hi", 5);

            assertThat(out).isEqualTo("hi\n");
            assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(8);
        } finally {
            if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()))
                    .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void executeStreaming_consumerThrows_destroysProcess(@TempDir Path tmp) throws Exception {
        Path pidFile = tmp.resolve("pid");
        assertThatThrownBy(() -> runner.executeStreaming(
            "echo $$ > " + pidFile + "; echo go; exec sleep 30",
            l -> { throw new IllegalStateException("bad"); }, 20))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("consumer");
        assertDies(readPid(pidFile));
    }
}
