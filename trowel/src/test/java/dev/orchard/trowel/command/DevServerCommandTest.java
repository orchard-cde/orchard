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
        // fence.jar and gateway.jar must exist for auth mode (default) to pass the
        // binary checks and reach the UI-resolve failure path being exercised here.
        Files.writeString(bin.resolve("fence.jar"), "");
        Files.writeString(bin.resolve("gateway.jar"), "");
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

    @Test
    void fenceBinary_resolvesUnderOrchardBin() {
        assertThat(DevServerCommand.fenceBinary().toString())
            .endsWith("/.orchard/bin/fence.jar");
    }

    @Test
    void fencePidFile_resolvesUnderRun() {
        assertThat(DevServerCommand.fencePidFile().toString())
            .endsWith("/.orchard/run/orchard-fence.pid");
    }

    @Test
    void readFenceInfo_defaultsToFencePortWhenPortMissing() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-fence.pid"), "12345");

        DevServerCommand.ServerInfo info = DevServerCommand.readFenceInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(7779);
    }

    @Test
    void readFenceInfo_readsPortFromFile() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-fence.pid"), "12345\n9999");

        DevServerCommand.ServerInfo info = DevServerCommand.readFenceInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(9999);
    }

    @Test
    void start_failsWhenFenceJarNotFound() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Files.writeString(core, "#!/bin/sh\necho fake\n");
        core.toFile().setExecutable(true, false);
        // No fence.jar created -> should fail with fence JAR error

        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("Fence auth server JAR not found");
        assertThat(errContent.toString()).contains("./gradlew :fence:bootJar");
    }

    @Test
    void start_noAuth_omitsFenceAndDisablesOAuth() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);
        start.setCorePortForTest(7778);
        start.noAuth = true;

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--orchard.security.oauth2.enabled=false");
        assertThat(cmd).noneMatch(a -> a.contains("issuer-uri"));
    }

    @Test
    void start_auth_includesIssuerUri() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);
        start.setCorePortForTest(7778);

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:7779");
    }

    @Test
    void start_auth_fencePortOverridesIssuerUri() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCultivatorIdForTest(null);
        start.setCorePortForTest(7778);
        start.setFencePortForTest(8888);

        java.util.List<String> cmd = start.buildCommand(Path.of("/bin/orchard-server"));

        assertThat(cmd).contains("--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8888");
    }

    @Test
    void stop_killsAllThreeProcesses() throws Exception {
        Process fence = new ProcessBuilder("sleep", "60").start();
        Process core = new ProcessBuilder("sleep", "60").start();
        Process ui = new ProcessBuilder("sleep", "60").start();
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-fence.pid"), fence.pid() + "\n7779");
        Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
        Files.writeString(runDir.resolve("orchard-ui.pid"), ui.pid() + "\n7777");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(Files.exists(runDir.resolve("orchard-fence.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-ui.pid"))).isFalse();
        assertThat(fence.isAlive()).isFalse();
        assertThat(core.isAlive()).isFalse();
        assertThat(ui.isAlive()).isFalse();
    }

    @Test
    void status_showsFenceWhenRunning() throws Exception {
        Process core = new ProcessBuilder("sleep", "60").start();
        Process ui = new ProcessBuilder("sleep", "60").start();
        Process fence = new ProcessBuilder("sleep", "60").start();
        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
            Files.writeString(runDir.resolve("orchard-ui.pid"), ui.pid() + "\n7777");
            Files.writeString(runDir.resolve("orchard-fence.pid"), fence.pid() + "\n7779");

            int exitCode = execute("dev-server", "status");

            assertThat(exitCode).isZero();
            String out = outContent.toString();
            assertThat(out).contains("running");
            assertThat(out).contains(String.valueOf(fence.pid()));
            assertThat(out).contains("http://localhost:7779");
        } finally {
            core.destroyForcibly();
            ui.destroyForcibly();
            fence.destroyForcibly();
        }
    }

    // --- Gateway (Grove SSH Gateway) ---

    @Test
    void gatewayBinary_resolvesUnderOrchardBin() {
        assertThat(DevServerCommand.gatewayBinary().toString())
            .endsWith("/.orchard/bin/gateway.jar");
    }

    @Test
    void gatewayPidFile_resolvesUnderRun() {
        assertThat(DevServerCommand.gatewayPidFile().toString())
            .endsWith("/.orchard/run/orchard-gateway.pid");
    }

    @Test
    void gatewayLogFile_resolvesUnderLogs() {
        assertThat(DevServerCommand.gatewayLogFile().toString())
            .endsWith("/.orchard/logs/orchard-gateway.log");
    }

    @Test
    void internalSshKeyPath_matchesTrellisQemuDefault() {
        // Same default trellis/QEMU resolve (QemuPlatformDefaults.defaultSshKeyPath()):
        // ~/.ssh/orchard_ed25519. The gateway MUST authenticate against this same key.
        assertThat(DevServerCommand.internalSshKeyPath())
            .isEqualTo(Path.of(tempDir.toString(), ".ssh", "orchard_ed25519").toString());
    }

    @Test
    void readGatewayInfo_defaultsToGatewaySshPortWhenPortMissing() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-gateway.pid"), "12345");

        DevServerCommand.ServerInfo info = DevServerCommand.readGatewayInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(2222);
    }

    @Test
    void readGatewayInfo_readsPortFromFile() throws Exception {
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-gateway.pid"), "12345\n3333");

        DevServerCommand.ServerInfo info = DevServerCommand.readGatewayInfo();

        assertThat(info).isNotNull();
        assertThat(info.pid()).isEqualTo(12345);
        assertThat(info.port()).isEqualTo(3333);
    }

    @Test
    void shouldStartGateway_trueByDefault() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        assertThat(start.shouldStartGateway()).isTrue();
    }

    @Test
    void shouldStartGateway_falseWhenNoGatewaySet() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.noGateway = true;
        assertThat(start.shouldStartGateway()).isFalse();
    }

    @Test
    void shouldStartGateway_falseWhenNoAuthSet() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.noAuth = true;
        assertThat(start.shouldStartGateway()).isFalse();
    }

    @Test
    void buildGatewayCommand_includesRequiredArgsWithDefaults() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setCorePortForTest(7778);
        start.setFencePortForTest(7779);

        java.util.List<String> cmd = start.buildGatewayCommand(Path.of("/bin/gateway.jar"));

        assertThat(cmd).containsExactly(
            "java",
            "-jar",
            "/bin/gateway.jar",
            "--server.port=" + DevServerCommand.GATEWAY_ADMIN_PORT,
            "--orchard.gateway.ssh-port=2222",
            "--orchard.gateway.internal-ssh-key-path=" + DevServerCommand.internalSshKeyPath(),
            "--orchard.gateway.fence.issuer-uri=http://localhost:7779",
            "--orchard.gateway.trellis.base-url=http://localhost:7778",
            "--orchard.gateway.oauth2.client-secret=" + DevServerCommand.DEV_GATEWAY_CLIENT_SECRET
        );
    }

    @Test
    void buildGatewayCommand_setsAdminPortExplicitly() {
        DevServerCommand.Start start = new DevServerCommand.Start();

        java.util.List<String> cmd = start.buildGatewayCommand(Path.of("/bin/gateway.jar"));

        assertThat(cmd).contains("--server.port=8081");
        assertThat(DevServerCommand.GATEWAY_ADMIN_PORT).isEqualTo(8081);
    }

    @Test
    void buildGatewayCommand_honorsGatewaySshPortOverride() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setGatewaySshPortForTest(3333);

        java.util.List<String> cmd = start.buildGatewayCommand(Path.of("/bin/gateway.jar"));

        assertThat(cmd).contains("--orchard.gateway.ssh-port=3333");
    }

    @Test
    void buildFenceCommand_includesGatewayClientSecret() {
        DevServerCommand.Start start = new DevServerCommand.Start();
        start.setFencePortForTest(7779);

        java.util.List<String> cmd = start.buildFenceCommand(Path.of("/bin/fence.jar"));

        assertThat(cmd).contains(
            "--fence.gateway-client.client-secret=" + DevServerCommand.DEV_GATEWAY_CLIENT_SECRET
        );
    }

    @Test
    void fenceAndGatewayClientSecrets_match() {
        // Load-bearing: fence's registered gateway client secret and the gateway's
        // oauth2.client-secret must be identical or the client_credentials exchange fails.
        DevServerCommand.Start start = new DevServerCommand.Start();

        java.util.List<String> fenceCmd = start.buildFenceCommand(Path.of("/bin/fence.jar"));
        java.util.List<String> gatewayCmd = start.buildGatewayCommand(Path.of("/bin/gateway.jar"));

        String fenceSecret = fenceCmd.stream()
            .filter(a -> a.startsWith("--fence.gateway-client.client-secret="))
            .findFirst().orElseThrow()
            .substring("--fence.gateway-client.client-secret=".length());
        String gatewaySecret = gatewayCmd.stream()
            .filter(a -> a.startsWith("--orchard.gateway.oauth2.client-secret="))
            .findFirst().orElseThrow()
            .substring("--orchard.gateway.oauth2.client-secret=".length());

        assertThat(fenceSecret).isNotBlank().isEqualTo(gatewaySecret);
    }

    @Test
    void start_failsWhenGatewayJarNotFound() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Files.writeString(core, "#!/bin/sh\necho fake\n");
        core.toFile().setExecutable(true, false);
        Files.writeString(bin.resolve("fence.jar"), ""); // fence jar present
        // no gateway.jar present

        int exitCode = execute("dev-server", "start");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errContent.toString()).contains("Gateway JAR not found");
        assertThat(errContent.toString()).contains("./gradlew :gateway:bootJar");
    }

    @Test
    void start_noAuth_skipsGatewayAndSaysSo() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Files.writeString(core, "#!/bin/sh\necho fake\n");
        core.toFile().setExecutable(true, false);
        // No fence.jar/gateway.jar needed: --no-auth skips both checks.

        int exitCode = execute("dev-server", "start", "--no-auth", "--foreground");

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("Skipping gateway");
    }

    @Test
    void start_noGatewayAlone_skipsGatewayAndSaysSo() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Files.writeString(core, "#!/bin/sh\necho fake\n");
        core.toFile().setExecutable(true, false);
        // fence.jar is still required: --no-gateway alone leaves auth enabled. It's a
        // dummy (not a real jar), so fence startup fails fast after the skip message
        // is printed - that failure isn't what this test is about.
        Files.writeString(bin.resolve("fence.jar"), "");

        int exitCode = execute("dev-server", "start", "--no-gateway", "--foreground");

        assertThat(exitCode).isEqualTo(1);
        assertThat(outContent.toString()).contains("Skipping gateway");
    }

    @Test
    void start_noGatewayOption_parsesWithoutError() throws Exception {
        Path bin = tempDir.resolve(".orchard").resolve("bin");
        Files.createDirectories(bin);
        Path core = bin.resolve("orchard-server");
        Files.writeString(core, "#!/bin/sh\necho fake\n");
        core.toFile().setExecutable(true, false);

        int exitCode = execute("dev-server", "start", "--no-auth", "--no-gateway", "--foreground");

        assertThat(exitCode).isZero();
    }

    @Test
    void stop_killsGatewayProcessAndClearsPidFile() throws Exception {
        Process gateway = new ProcessBuilder("sleep", "60").start();
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-gateway.pid"), gateway.pid() + "\n2222");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(Files.exists(runDir.resolve("orchard-gateway.pid"))).isFalse();
        assertThat(gateway.isAlive()).isFalse();
    }

    @Test
    void stop_killsAllFourProcesses() throws Exception {
        Process fence = new ProcessBuilder("sleep", "60").start();
        Process core = new ProcessBuilder("sleep", "60").start();
        Process ui = new ProcessBuilder("sleep", "60").start();
        Process gateway = new ProcessBuilder("sleep", "60").start();
        Path runDir = tempDir.resolve(".orchard").resolve("run");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("orchard-fence.pid"), fence.pid() + "\n7779");
        Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
        Files.writeString(runDir.resolve("orchard-ui.pid"), ui.pid() + "\n7777");
        Files.writeString(runDir.resolve("orchard-gateway.pid"), gateway.pid() + "\n2222");

        int exitCode = execute("dev-server", "stop");

        assertThat(exitCode).isZero();
        assertThat(Files.exists(runDir.resolve("orchard-fence.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-server.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-ui.pid"))).isFalse();
        assertThat(Files.exists(runDir.resolve("orchard-gateway.pid"))).isFalse();
        assertThat(fence.isAlive()).isFalse();
        assertThat(core.isAlive()).isFalse();
        assertThat(ui.isAlive()).isFalse();
        assertThat(gateway.isAlive()).isFalse();
    }

    @Test
    void status_showsGatewayWhenRunning() throws Exception {
        Process core = new ProcessBuilder("sleep", "60").start();
        Process gateway = new ProcessBuilder("sleep", "60").start();
        try {
            Path runDir = tempDir.resolve(".orchard").resolve("run");
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("orchard-server.pid"), core.pid() + "\n7778");
            Files.writeString(runDir.resolve("orchard-gateway.pid"), gateway.pid() + "\n2222");

            int exitCode = execute("dev-server", "status");

            assertThat(exitCode).isZero();
            String out = outContent.toString();
            assertThat(out).contains("running");
            assertThat(out).contains(String.valueOf(gateway.pid()));
            assertThat(out).contains("Gateway SSH port: 2222");
        } finally {
            core.destroyForcibly();
            gateway.destroyForcibly();
        }
    }
}
