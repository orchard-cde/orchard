package dev.orchard.apiary.support;

import dev.orchard.nursery.CommandRunner;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * In-memory {@link CommandRunner} for benchmarks. Never throws, so
 * {@code ensureBinaryInstalled}'s {@code command -v opencode} probe always succeeds and the
 * (dead) curl install branch stays out of every measurement.
 */
public final class StubCommandRunner implements CommandRunner {

    private static final String STDOUT = "12345";

    @Override
    public String execute(String command) {
        return STDOUT;
    }

    @Override
    public String execute(String command, long timeoutSeconds) {
        return STDOUT;
    }

    @Override
    public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds) {
        lineConsumer.accept(STDOUT);
    }

    @Override
    public Optional<String> readFile(String remotePath) {
        return Optional.empty();
    }
}
