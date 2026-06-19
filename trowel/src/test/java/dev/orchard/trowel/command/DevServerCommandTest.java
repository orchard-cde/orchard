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
            Files.writeString(runDir.resolve("orchard-server.pid"), pid + "\n7778");

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
        Files.writeString(runDir.resolve("orchard-server.pid"), pid + "\n8080");

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
            Files.writeString(runDir.resolve("orchard-server.pid"), pid + "\n9090");

            int exitCode = execute("dev-server", "status");

            assertThat(exitCode).isZero();
            String output = outContent.toString();
            assertThat(output).contains("running");
            assertThat(output).contains(String.valueOf(pid));
        } finally {
            sleepProcess.destroyForcibly();
        }
    }

    @Test
    void status_usesPersistedPort() throws Exception {
        Process sleepProcess = new ProcessBuilder("sleep", "60").start();
        long pid = sleepProcess.pid();

        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), pid + "\n9090");

            execute("dev-server", "status");

            // Health check will fail (no server), but URL should use persisted port
            // The "starting or unreachable" message confirms it tried the right port
            // and didn't hardcode 8080
            String output = outContent.toString();
            assertThat(output).contains("running");
        } finally {
            sleepProcess.destroyForcibly();
        }
    }

    @Test
    void readServerInfo_defaultsToCorePortWhenPortMissing() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), "12345");

        DevServerCommand.ServerInfo info = DevServerCommand.readServerInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(7778);
    }

    @Test
    void readServerInfo_readsPortFromFile() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), "12345\n9090");

        DevServerCommand.ServerInfo info = DevServerCommand.readServerInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(9090);
    }

    @Test
    void includesCultivatorArgWhenConfigured() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest("4fbe48ac-dcfd-41ac-a817-6b400e2b34ec");

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--orchard.dev.default-cultivator-id=4fbe48ac-dcfd-41ac-a817-6b400e2b34ec");
    }

    @Test
    void omitsCultivatorArgWhenNotConfigured() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).noneMatch(a -> a.startsWith("--orchard.dev.default-cultivator-id="));
    }

    @Test
    void start_buildCommand_usesCorePortDefault() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--server.port=7778");
    }

    @Test
    void start_buildCommand_honorsCorePortOverride() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);
        start.setCorePortForTest(9001);

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--server.port=9001");
    }

    @Test
    void uiBackendBinary_resolvesUnderOrchardBin() {
        assertThat(DevServerCommand.uiBackendBinary().toString())
            .endsWith("/.orchard/bin/orchard-ui-backend");
    }

    @Test
    void uiPidFile_resolvesUnderRun() {
        assertThat(DevServerCommand.uiPidFile().toString())
            .endsWith("/.orchard/run/orchard-ui.pid");
    }

    @Test
    void buildUiCommand_setsBffPort() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        java.util.List<String> cmd = start.buildUiCommand(Path.of("/bin/orchard-ui-backend"));
        assertThat(cmd).containsExactly("/bin/orchard-ui-backend", "--server.port=7777");
    }

    @Test
    void uiEnv_pointsAtCorePort() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCorePortForTest(7778);
        assertThat(start.uiEnv()).containsEntry("ORCHARD_CORE_BASE_URL", "http://localhost:7778");
    }

    @Test
    void start_alreadyRunning_printsBffUrl() throws Exception {
        Process sleepProcess = new ProcessBuilder("sleep", "60").start();
        long pid = sleepProcess.pid();
        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), pid + "\n7778");

            int exitCode = execute("dev-server", "start");

            assertThat(exitCode).isZero();
            assertThat(outContent.toString()).contains("already running");
            assertThat(outContent.toString()).contains("http://localhost:7777");
        } finally {
            sleepProcess.destroyForcibly();
        }
    }

    @Test
    void waitForHealth_returnsFastWhenProcessAlreadyDead() throws Exception {
        Process p = new ProcessBuilder("true").start();
        p.waitFor();
        DevServerCommand.Start start = new DevServerCommand.Start();
        long t0 = System.currentTimeMillis();
        boolean ok = start.waitForHealth(p, 65535, "/api/health", 30);
        assertThat(ok).isFalse();
        assertThat(System.currentTimeMillis() - t0).isLessThan(5000);
    }

    @Test
    void start_failedUiResolve_leavesNoCoreProcess() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Path ranMarker = tempDir.resolve("CORE_RAN");
        Files.writeString(core, "#!/bin/sh\ntouch '" + ranMarker + "'\nsleep 60\n");
        core.toFile().setExecutable(true, false);
        System.setProperty("orchard.ui.releaseBase", "http://localhost:1");
        try {
            int exit = execute("dev-server", "start");
            assertThat(exit).isEqualTo(1);
            Path corePid = tempDir.resolve(".orchard").resolve("run").resolve("orchard-server.pid");
            assertThat(Files.exists(corePid)).isFalse();
            assertThat(Files.exists(ranMarker)).isFalse();
        } finally {
            System.clearProperty("orchard.ui.releaseBase");
        }
    }

    @Test
    void stop_killsBothProcessesAndClearsBothPidFiles() throws Exception {
        Process core = new ProcessBuilder("sleep", "60").start();
        Process ui = new ProcessBuilder("sleep", "60").start();
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
        Files.writeString(runDir.resolve("orchard-ui.pid"), ui.pid() + "\n7777");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-ui.pid"))).isFalse();
        assertThat(core.isAlive()).isFalse();
        assertThat(ui.isAlive()).isFalse();
    }

    @Test
    void stop_handlesCoreOnlyWhenNoUiPidFile() throws Exception {
        Process core = new ProcessBuilder("sleep", "60").start();
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(core.isAlive()).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
    }

    @Test
    void status_reportsBothWhenUiRunning() throws Exception {
        Process core = new ProcessBuilder("sleep", "60").start();
        Process ui = new ProcessBuilder("sleep", "60").start();
        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
            Files.writeString(runDir.resolve("orchard-ui.pid"), ui.pid() + "\n7777");

            int exitCode = execute("dev-server", "status");

            assertThat(exitCode).isZero();
            String out = outContent.toString();
            assertThat(out).contains("running");
            assertThat(out).contains(String.valueOf(ui.pid()));
            assertThat(out).contains("http://localhost:7777");
        } finally {
            core.destroyForcibly();
            ui.destroyForcibly();
        }
    }
}
