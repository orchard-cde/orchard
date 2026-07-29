package dev.orchard.trowel;

import dev.orchard.trowel.auth.NoAuthProvider;
import dev.orchard.trowel.auth.TokenAuthProvider;
import dev.orchard.trowel.config.ConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrowelTargetResolutionTest {

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;

    @BeforeEach
    void setUp() throws Exception {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());

        Files.createDirectories(ConfigLoader.configDir());
        Files.writeString(ConfigLoader.tomlFile(), """
                active = "local"

                [targets.local]
                server = "http://localhost:7778"
                cultivator = "local-uuid"
                """);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setProperty("user.home", originalHome);
    }

    private int execute(String... args) {
        return Trowel.createCommandLine().execute(args);
    }

    @Test
    void unknownTarget_returnsExitOneWithoutKillingJvm() {
        // Reaching this assertion at all proves resolveConfigTarget() no longer
        // calls System.exit (which would terminate the in-process test JVM).
        int exitCode = execute("--target", "bogus", "config", "show");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("target 'bogus' not found");
    }

    @Test
    void knownTarget_resolvesSuccessfully() {
        int exitCode = execute("--target", "local", "config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://localhost:7778");
    }

    @Test
    void target_withNoStoredToken_usesNoAuthProvider() throws Exception {
        Files.writeString(ConfigLoader.tomlFile(), """
                active = "local"

                [targets.local]
                server = "http://localhost:7778"
                cultivator = "local-uuid"
                """);

        var trowel = new Trowel();
        var authProvider = trowel.getAuthProvider();
        assertThat(authProvider).isInstanceOf(NoAuthProvider.class);
    }

    @Test
    void target_withStoredToken_usesTokenAuthProvider() throws Exception {
        long futureExpiry = (System.currentTimeMillis() / 1000) + 3600;
        Files.writeString(ConfigLoader.tomlFile(), """
                active = "local"

                [targets.local]
                server = "http://localhost:7778"
                cultivator = "local-uuid"
                fence_server = "http://localhost:7779"
                access_token = "my-access-token"
                refresh_token = "my-refresh-token"
                expires_at = %d
                """.formatted(futureExpiry));

        var trowel = new Trowel();
        var authProvider = trowel.getAuthProvider();
        assertThat(authProvider).isInstanceOf(TokenAuthProvider.class);
    }
}
