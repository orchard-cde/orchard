package dev.orchard.vine;

import dev.orchard.core.model.ContainerPlot;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link Vine} for container-backed groves: runs commands inside the fruit with
 * {@code <runtime> exec}, driven through a local command runner on the trellis host.
 *
 * <p>The container is resolved by plot label on every call, so a recreated fruit is found without
 * the vine holding a stale container id. Commands run as the plot's remote user when it has one,
 * so files they write in the bind-mounted workspace are not owned by the container's root.
 */
public final class ContainerVine implements Vine {

    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final CommandRunner runner;

    public ContainerVine(ContainerPlot plot, ContainerCommandRunner engine) {
        this.runner = new ExecRunner(plot.runtime().cli(), plot.id(), plot.remoteUser(), engine);
    }

    @Override
    public CommandRunner commands() {
        return runner;
    }

    private record ExecRunner(String cli, UUID plotId, String remoteUser, ContainerCommandRunner engine)
            implements CommandRunner {

        @Override
        public String execute(String command) throws IOException, InterruptedException {
            return execute(command, DEFAULT_TIMEOUT_SECONDS);
        }

        @Override
        public String execute(String command, long timeoutSeconds) throws IOException, InterruptedException {
            return engine.execute(execCommand(command), timeoutSeconds);
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds)
                throws IOException, InterruptedException {
            engine.executeStreaming(execCommand(command), lineConsumer, timeoutSeconds);
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
            return ShellQuote.join(ContainerExec.of(cli, resolveContainer()).user(remoteUser).shell(command).argv());
        }

        private String resolveContainer() throws IOException, InterruptedException {
            return ContainerLabels.containerIds(engine, cli, plotId, false, DEFAULT_TIMEOUT_SECONDS).stream().findFirst()
                .orElseThrow(() -> new IOException("no running fruit for plot " + plotId));
        }
    }
}
