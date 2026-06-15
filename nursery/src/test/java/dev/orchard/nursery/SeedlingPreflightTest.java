package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import dev.orchard.core.model.SeedlingState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the +P1 provisioning preflight contract: before declaring a Seedling READY
 * (SAPLING), the provider must SSH in and confirm {@code devcontainer --version} matches
 * the pinned {@link DevcontainerCliConfig#version()}.
 *
 * <p>Exercises the package-private {@link SeedlingProvider#verifyDevcontainerCli(Seedling,
 * String, CommandRunner)} test seam — the production default impl uses {@link SshExecutor},
 * which is covered by the integration tests.
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

    private static Seedling stubSeedling() {
        return new Seedling(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "i-fake",
            "10.0.0.1",
            22,
            SeedlingState.SAPLING,
            Seedling.SeedlingSpec.small(),
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void correctVersionPasses() {
        Seedling seedling = stubSeedling();
        CommandRunner runner = new CannedVersionRunner("0.87.0", false);

        assertThatCode(() -> SeedlingProvider.verifyDevcontainerCli(seedling, "0.87.0", runner))
            .doesNotThrowAnyException();
    }

    @Test
    void wrongVersionThrows() {
        Seedling seedling = stubSeedling();
        CommandRunner runner = new CannedVersionRunner("0.86.0", false);

        assertThatThrownBy(() -> SeedlingProvider.verifyDevcontainerCli(seedling, "0.87.0", runner))
            .isInstanceOf(SeedlingProvisioningException.class)
            .hasMessageContaining("version mismatch")
            .hasMessageContaining("expected 0.87.0")
            .hasMessageContaining("got 0.86.0");
    }

    @Test
    void cliMissingThrows() {
        Seedling seedling = stubSeedling();
        CommandRunner runner = new CannedVersionRunner("(unused)", true);

        assertThatThrownBy(() -> SeedlingProvider.verifyDevcontainerCli(seedling, "0.87.0", runner))
            .isInstanceOf(SeedlingProvisioningException.class)
            .hasMessageContaining("missing devcontainer CLI")
            .hasRootCauseInstanceOf(IOException.class);
    }
}
