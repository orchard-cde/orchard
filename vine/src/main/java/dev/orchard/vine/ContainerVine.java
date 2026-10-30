package dev.orchard.vine;

import dev.orchard.core.model.ContainerPlot;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link Vine} for container-backed groves: runs commands inside the fruit with
 * {@code <runtime> exec}, driven through a local command runner on the trellis host.
 *
 * <p>The container is resolved by plot label on every call, so a recreated fruit is found without
 * the vine holding a stale container id.
 */
public final class ContainerVine implements Vine {

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final CommandRunner runner;

    public ContainerVine(ContainerPlot plot, CommandRunner local) {
        this.runner = new ExecRunner(plot.runtime().cli(), plot.id().toString(), local);
    }

    @Override
    public CommandRunner commands() {
        return runner;
    }

    private record ExecRunner(String cli, String plotId, CommandRunner local) implements CommandRunner {

        @Override
        public String execute(String command) throws IOException, InterruptedException {
            return execute(command, DEFAULT_TIMEOUT_SECONDS);
        }

        @Override
        public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
            return local.execute(execCommand(command), timeoutSeconds);
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
                throws IOException, InterruptedException {
            local.executeStreaming(execCommand(command), lineConsumer, timeoutSeconds);
        }

        @Override
        public Optional<String> readFile(String path) {
            try {
                String content = execute("cat " + ShellQuote.quote(path));
                return content.isEmpty() ? Optional.empty() : Optional.of(content);
            } catch (IOException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        private String execCommand(String command) throws IOException, InterruptedException {
            String id = resolveContainer();
            return cli + " exec " + ShellQuote.quote(id) + " /bin/sh -c " + ShellQuote.quote(command);
        }

        private String resolveContainer() throws IOException, InterruptedException {
            String filter = ShellQuote.quote("label=orchard.plot.id=" + plotId);
            String out = local.execute(cli + " ps -q --filter " + filter).trim();
            if (out.isEmpty()) {
                throw new IOException("no running fruit for plot " + plotId);
            }
            return out.lines().findFirst().orElseThrow();
        }
    }
}
