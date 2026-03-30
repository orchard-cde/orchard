package dev.orchard.e2e;

import dev.orchard.trowel.Trowel;
import org.junit.jupiter.api.*;
import picocli.CommandLine;

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
        return new CommandLine(new Trowel()).execute(args);
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
    void configInit_createsFile() {
        int exitCode = execute("config", "init");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Created configuration");
        assertThat(tempDir.resolve(".orchard/config.properties")).exists();
    }

    @Test
    @Order(3)
    void configShow_afterInit_showsValues() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("server =");
        assertThat(output).contains("cultivator =");
    }

    @Test
    @Order(4)
    void configSet_updatesValue() {
        int exitCode = execute("config", "set", "--server", "http://custom:9090");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Set server");
    }

    @Test
    @Order(5)
    void configShow_afterSet_showsUpdated() {
        int exitCode = execute("config", "show");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("http://custom:9090");
    }

    @Test
    @Order(6)
    void devServer_showsUsage() {
        int exitCode = execute("dev-server");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("start");
        assertThat(output).contains("stop");
        assertThat(output).contains("status");
    }

    @Test
    @Order(7)
    void devServerStatus_showsStopped() {
        int exitCode = execute("dev-server", "status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("stopped");
    }

    @Test
    @Order(8)
    void devServerStop_handlesNotRunning() {
        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("not running");
    }

    @Test
    @Order(9)
    void devServerStart_failsNoBinary() {
        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("binary not found");
    }
}
