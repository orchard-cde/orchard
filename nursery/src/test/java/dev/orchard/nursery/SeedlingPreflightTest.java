package dev.orchard.nursery;

import dev.orchard.core.model.Plot;
import dev.orchard.core.model.VmPlot;
import dev.orchard.vine.CommandRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the +P1 provisioning preflight contract: before declaring a Seedling READY
 * (SAPLING), the caller must confirm {@code devcontainer --version} matches the pinned
 * {@link DevcontainerCliConfig#version()}.
 *
 * <p>Exercises {@link GroveProvider#verifyDevcontainerCli(Plot, String, CommandRunner)},
 * the sole production path, against a canned {@link CommandRunner} rather than a real substrate
 * connection — reaching the substrate is {@link GroveProvider}'s business (see
 * {@link GroveProvider#vine}), which is covered by the integration tests.
 */
class SeedlingPreflightTest {

    /** Minimal {@link CommandRunner} that returns a canned version string (or throws). */
    static class CannedVersionRunner implements CommandRunner {
        private final String version;
        private final boolean throwOnExecute;

        CannedVersionRunner(String version, boolean throwOnExecute) {
            this.version = version;
            this.throwOnExecute = throwOnExecute;
        }

        @Override
        public String execute(String command) throws IOException {
            if (throwOnExecute) {
                throw new IOException("CLI missing on PATH");
            }
            return version + "\n";
        }

        @Override
        public String execute(String command, long timeoutSeconds) throws IOException {
            return execute(command);
        }

        @Override
        public Optional<String> readFile(String remotePath) {
            return Optional.empty();
        }

        @Override
        public void executeStreaming(String command, Consumer<String> lineConsumer, long timeoutSeconds) {
            throw new UnsupportedOperationException();
        }
    }

    private static VmPlot stubPlot() {
        return TestPlots.fake();
    }

    @Test
    void correctVersionPasses() {
        VmPlot plot = stubPlot();
        CommandRunner runner = new CannedVersionRunner("0.87.0", false);

        assertThatCode(() -> GroveProvider.verifyDevcontainerCli(plot, "0.87.0", runner))
            .doesNotThrowAnyException();
    }

    @Test
    void wrongVersionThrows() {
        VmPlot plot = stubPlot();
        CommandRunner runner = new CannedVersionRunner("0.86.0", false);

        assertThatThrownBy(() -> GroveProvider.verifyDevcontainerCli(plot, "0.87.0", runner))
            .isInstanceOf(SeedlingProvisioningException.class)
            .hasMessageContaining("version mismatch")
            .hasMessageContaining("expected 0.87.0")
            .hasMessageContaining("got 0.86.0");
    }

    @Test
    void cliMissingThrows() {
        VmPlot plot = stubPlot();
        CommandRunner runner = new CannedVersionRunner("(unused)", true);

        assertThatThrownBy(() -> GroveProvider.verifyDevcontainerCli(plot, "0.87.0", runner))
            .isInstanceOf(SeedlingProvisioningException.class)
            .hasMessageContaining("missing devcontainer CLI")
            .hasRootCauseInstanceOf(IOException.class);
    }
}
