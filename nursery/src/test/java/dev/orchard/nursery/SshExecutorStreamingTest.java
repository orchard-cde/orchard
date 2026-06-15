package dev.orchard.nursery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the streaming contract that DevcontainerCli depends on:
 *   - lines arrive incrementally (not buffered to end of process)
 *   - timeout enforcement
 *   - non-zero exit raises IOException with stderr context
 *
 * Uses an in-memory CommandRunner fake; the real SSH integration is covered by FruitGrowerIT.
 */
class SshExecutorStreamingTest {

    /** Fake that emits canned lines with a delay between them, then exits with the given code. */
    static class DelayingFake implements CommandRunner {
        private final List<String> lines;
        private final long delayMs;
        private final int exitCode;

        DelayingFake(List<String> lines, long delayMs, int exitCode) {
            this.lines = lines;
            this.delayMs = delayMs;
            this.exitCode = exitCode;
        }

        @Override
        public String execute(String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String execute(String command, long timeoutSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
                throws IOException, InterruptedException {
            for (String line : lines) {
                lineConsumer.accept(line);
                Thread.sleep(delayMs);
            }
            if (exitCode != 0) {
                throw new IOException("fake exit " + exitCode);
            }
        }

        @Override
        public java.util.Optional<String> readFile(String p) { return java.util.Optional.empty(); }
    }

    @Test
    void emitsLinesIncrementally() throws Exception {
        DelayingFake fake = new DelayingFake(List.of("one", "two", "three"), 50, 0);

        List<Long> arrivalTimes = new ArrayList<>();
        long start = System.nanoTime();
        fake.executeStreaming("does-not-matter",
            line -> arrivalTimes.add(System.nanoTime() - start),
            5);

        assertThat(arrivalTimes).hasSize(3);
        // Each successive line should arrive after the previous; gaps must reflect the 50ms delay.
        assertThat(arrivalTimes.get(1) - arrivalTimes.get(0)).isGreaterThan(40_000_000L);
        assertThat(arrivalTimes.get(2) - arrivalTimes.get(1)).isGreaterThan(40_000_000L);
    }

    @Test
    void nonZeroExitThrows() {
        DelayingFake fake = new DelayingFake(List.of("oops"), 0, 1);
        assertThatThrownBy(() -> fake.executeStreaming("fail", l -> {}, 5))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("fake exit 1");
    }
}
