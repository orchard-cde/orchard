package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevServerCommandTest {

    @TempDir
    Path tempDir;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        // Redirect ~/.orchard to temp dir
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
    void devServer_showsUsageWhenNoSubcommand() {
        int exitCode = execute("dev-server");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("Manage the local Orchard development server");
        assertThat(output).contains("start");
        assertThat(output).contains("stop");
        assertThat(output).contains("status");
    }

    @Test
    void status_showsStoppedWhenNoPidFile() {
        int exitCode = execute("dev-server", "status");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("stopped");
    }

    @Test
    void status_showsStoppedWhenStalePidFile() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        // Write a PID that doesn't exist (use a very high number)
        Files.writeString(runDir.resolve("orchard-server.pid"), "999999999");

        int exitCode = execute("dev-server", "status");

        assertThat(exitCode).isZero();
        String output = outContent.toString();
        assertThat(output).contains("stopped");
        // Stale PID file should be cleaned up
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
    }

    @Test
    void start_failsWhenBinaryNotFound() {
        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        String errOutput = errContent.toString();
        assertThat(errOutput).contains("server binary not found");
        assertThat(errOutput).contains("./gradlew :trellis:nativeCompile");
    }

    @Test
    void stop_handlesNotRunningGracefully() {
        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("not running");
    }

    @Test
    void stop_handlesStalePidFile() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), "999999999");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        // PID file should be cleaned up
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
    }

    @Test
    void start_reportsAlreadyRunningWhenProcessExists() throws Exception {
        // Start a real background process we control (sleep)
        Process sleepProcess = new ProcessBuilder("sleep", "60").start();
        long pid = sleepProcess.pid();

        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), String.valueOf(pid));

            int exitCode = execute("dev-server", "start");

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("already running");
        } finally {
            sleepProcess.destroyForcibly();
        }
    }

    @Test
    void stop_killsRunningProcess() throws Exception {
        // Start a real background process we control
        Process sleepProcess = new ProcessBuilder("sleep", "60").start();
        long pid = sleepProcess.pid();

        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), String.valueOf(pid));

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("stopped");
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();

        // Process should be terminated
        assertThat(sleepProcess.isAlive()).isFalse();
    }

    @Test
    void status_showsRunningWhenProcessAlive() throws Exception {
        Process sleepProcess = new ProcessBuilder("sleep", "60").start();
        long pid = sleepProcess.pid();

        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), String.valueOf(pid));

            int exitCode = execute("dev-server", "status");

            assertThat(exitCode).isZero();
            String output = outContent.toString();
            assertThat(output).contains("running");
            assertThat(output).contains(String.valueOf(pid));
        } finally {
            sleepProcess.destroyForcibly();
        }
    }
}
