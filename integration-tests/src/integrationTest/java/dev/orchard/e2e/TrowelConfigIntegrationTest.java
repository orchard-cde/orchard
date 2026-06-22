package dev.orchard.e2e;

import dev.orchard.trowel.Trowel;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrowelConfigIntegrationTest {

    private Path tempDir;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;

    @BeforeAll
    void createTempDir() throws Exception {
        tempDir = Files.createTempDirectory("trowel-config-test");
    }

    @AfterAll
    void cleanupTempDir() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
        }
    }

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
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
    @Order(1)
    void configShow_noFile_showsDefault() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("no configuration file found");
    }

    @Test
    @Order(2)
    void configInit_createsTomlFile() {
        int exitCode = execute("config", "init");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Created configuration");
        assertThat(tempDir.resolve(".orchard/config.toml")).exists();
        assertThat(tempDir.resolve(".orchard/config.properties")).doesNotExist();
    }

    @Test
    @Order(3)
    void configShow_afterInit_showsTomlTargets() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("Active target:");
        assertThat(output).contains("server");
        assertThat(output).contains("cultivator");
    }

    @Test
    @Order(4)
    void configSet_updatesActiveTarget() {
        int exitCode = execute("config", "set", "--server", "http://custom:9090");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Set server");
    }

    @Test
    @Order(5)
    void configShow_afterSet_showsUpdatedServer() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://custom:9090");
    }

    @Test
    @Order(6)
    void configTargetList_showsActiveTarget() {
        int exitCode = execute("config", "target", "list");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("* ");
    }

    @Test
    @Order(7)
    void configTargetAdd_addsNewTarget() {
        int exitCode = execute("config", "target", "add", "staging",
                "--server", "https://staging.example.com",
                "--cultivator", "staging-uuid");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Added target 'staging'");
    }

    @Test
    @Order(8)
    void configTargetList_showsBothTargets() {
        int exitCode = execute("config", "target", "list");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("staging");
        assertThat(output).contains("https://staging.example.com");
    }

    @Test
    @Order(9)
    void configTargetSet_switchesActiveTarget() {
        int exitCode = execute("config", "target", "set", "staging");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Active target set to 'staging'");
    }

    @Test
    @Order(10)
    void targetFlag_overridesActiveTarget() {
        int exitCode = execute("--target", "local", "config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://custom:9090");
    }

    @Test
    @Order(11)
    void configTargetRemove_activeTarget_fails() {
        int exitCode = execute("config", "target", "remove", "staging");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("Cannot remove the active target");
    }

    @Test
    @Order(12)
    void configTargetRemove_nonActiveTarget_removes() {
        execute("config", "target", "set", "local"); // switch back first
        outContent.reset();

        int exitCode = execute("config", "target", "remove", "staging");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Removed target 'staging'");
    }

    @Test
    @Order(13)
    void legacyPropertiesFile_readsAsDefaultTarget() throws Exception {
        // Use a fresh temp dir for this test to avoid TOML file interference
        Path legacyDir = Files.createTempDirectory("trowel-legacy-test");
        System.setProperty("user.home", legacyDir.toString());
        try {
            Files.createDirectories(legacyDir.resolve(".orchard"));
            Files.writeString(legacyDir.resolve(".orchard/config.properties"),
                    "server=http://legacy:8888\ncultivator=legacy-uuid\n");

            int exitCode = execute("config", "show");

            assertThat(exitCode).isZero();
            String output = outContent.toString();
            assertThat(output).contains("legacy format");
            assertThat(output).contains("http://legacy:8888");
        } finally {
            System.setProperty("user.home", tempDir.toString());
            Files.walk(legacyDir).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    @Order(14)
    void devServer_showsUsage() {
        int exitCode = execute("dev-server");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("start");
        assertThat(output).contains("stop");
        assertThat(output).contains("status");
    }

    @Test
    @Order(15)
    void devServerStatus_showsStopped() {
        int exitCode = execute("dev-server", "status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("stopped");
    }

    @Test
    @Order(16)
    void devServerStop_handlesNotRunning() {
        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("not running");
    }

    @Test
    @Order(17)
    void devServerStart_failsNoBinary() {
        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("binary not found");
    }
}
