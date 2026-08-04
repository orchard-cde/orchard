package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(
    name = "dev-server",
    description = "Manage the local Orchard development server",
    subcommands = {
        DevServerCommand.Start.class,
        DevServerCommand.Stop.class,
        DevServerCommand.Status.class
    }
)
public class DevServerCommand implements Callable<Integer> {

    // Dev-only shared secret between fence's gateway OAuth2 client and the gateway's
    // client_credentials login. Fence and gateway MUST be launched with the same value
    // or the gateway's token exchange against fence will fail with invalid_client.
    static final String DEV_GATEWAY_CLIENT_SECRET = "orchard-dev-gateway-secret";

    // The gateway's admin/actuator port (server.port), separate from its SSH port.
    static final int GATEWAY_ADMIN_PORT = 8081;

    @ParentCommand
    Trowel parent;

    static Path orchardHome() {
        return Path.of(System.getProperty("user.home"), ".orchard");
    }

    static Path pidFile() {
        return orchardHome().resolve("run").resolve("orchard-server.pid");
    }

    static Path logFile() {
        return orchardHome().resolve("logs").resolve("orchard-server.log");
    }

    static Path serverBinary() {
        return orchardHome().resolve("bin").resolve("orchard-server");
    }

    static Path uiPidFile() {
        return orchardHome().resolve("run").resolve("orchard-ui.pid");
    }

    static Path uiLogFile() {
        return orchardHome().resolve("logs").resolve("orchard-ui.log");
    }

    static Path uiBackendBinary() {
        return orchardHome().resolve("bin").resolve("orchard-ui-backend");
    }

    static Path fenceBinary() {
        return orchardHome().resolve("bin").resolve("fence.jar");
    }

    static Path fencePidFile() {
        return orchardHome().resolve("run").resolve("orchard-fence.pid");
    }

    static Path fenceLogFile() {
        return orchardHome().resolve("logs").resolve("orchard-fence.log");
    }

    static Path gatewayBinary() {
        return orchardHome().resolve("bin").resolve("gateway.jar");
    }

    static Path gatewayPidFile() {
        return orchardHome().resolve("run").resolve("orchard-gateway.pid");
    }

    static Path gatewayLogFile() {
        return orchardHome().resolve("logs").resolve("orchard-gateway.log");
    }

    // Same key path trellis/QEMU resolves by default (see QemuPlatformDefaults.defaultSshKeyPath()
    // and GroveController.resolveSshKeyPath()): ~/.ssh/orchard_ed25519. The gateway's
    // internal-ssh-key-path MUST match this, since it's the key authorized on seedlings.
    static String internalSshKeyPath() {
        return Path.of(System.getProperty("user.home"), ".ssh", "orchard_ed25519").toString();
    }

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "start", description = "Start the local Orchard development server")
    public static class Start implements Callable<Integer> {

        @ParentCommand
        DevServerCommand parent;

        // Test seam: overrides the parent-resolved cultivator id when set (null = use parent chain).
        private String cultivatorIdOverride;
        void setCultivatorIdForTest(String cultivatorId) { this.cultivatorIdOverride = cultivatorId; }

        private String resolveCultivatorId() {
            if (cultivatorIdOverride != null) {
                return cultivatorIdOverride;
            }
            return parent != null && parent.parent != null ? parent.parent.getCultivatorId() : null;
        }

        @Option(names = {"--foreground", "-f"}, description = "Run in foreground (default: background)")
        boolean foreground;

        @Option(names = {"--verbose", "-v"}, description = "Enable debug logging")
        boolean verbose;

        @Option(names = {"--port", "-p"}, description = "UI (browser) port (default: 7777)", defaultValue = "7777")
        int port = 7777;

        @Option(names = {"--core-port"}, description = "Orchard core API port (default: 7778)", defaultValue = "7778")
        int corePort = 7778;

        @Option(names = {"--no-ui"}, description = "Start orchard core only, without the UI")
        boolean noUi;

        @Option(names = {"--ui-version"}, description = "orchard-ui-backend release version to run")
        String uiVersion;

        @Option(names = {"--open"}, description = "Open the UI in your browser once it is ready")
        boolean open;

        @Option(names = {"--fence-port"}, description = "Fence auth server port (default: 7779)", defaultValue = "7779")
        int fencePort = 7779;

        @Option(names = {"--no-auth"}, description = "Start without the fence auth server (disables OAuth2)")
        boolean noAuth;

        @Option(names = {"--gateway-ssh-port"}, description = "SSH gateway port (default: 2222)", defaultValue = "2222")
        int gatewaySshPort = 2222;

        @Option(names = {"--no-gateway"}, description = "Start without the SSH gateway")
        boolean noGateway;

        // Test seam: override the core port without picocli parsing.
        void setCorePortForTest(int p) { this.corePort = p; }

        // Test seam: override the fence port without picocli parsing.
        void setFencePortForTest(int p) { this.fencePort = p; }

        // Test seam: override the gateway SSH port without picocli parsing.
        void setGatewaySshPortForTest(int p) { this.gatewaySshPort = p; }

        // The gateway requires client_credentials auth against fence, so it cannot run
        // without fence up. --no-auth implies --no-gateway.
        boolean shouldStartGateway() {
            return !noAuth && !noGateway;
        }

        @Override
        public Integer call() {
            try {
                if (isServerRunning()) {
                    System.out.println("Orchard dev-server is already running.");
                    printConnectionInfo(port);
                    return 0;
                }

                Path coreBinary = serverBinary();
                if (!Files.isExecutable(coreBinary)) {
                    System.err.println("Orchard server binary not found at: " + coreBinary);
                    System.err.println();
                    System.err.println("Build it from source with:");
                    System.err.println("  ./gradlew :trellis:nativeCompile");
                    System.err.println();
                    System.err.println("Then install it:");
                    System.err.println("  mkdir -p " + coreBinary.getParent());
                    System.err.println("  cp trellis/build/native/nativeCompile/orchard-server " + coreBinary);
                    return 1;
                }

                ensureDirectories();

                if (!noAuth) {
                    Path fenceJar = fenceBinary();
                    if (!Files.isRegularFile(fenceJar) || !Files.isReadable(fenceJar)) {
                        System.err.println("Fence auth server JAR not found at: " + fenceJar);
                        System.err.println();
                        System.err.println("Build it from source with:");
                        System.err.println("  ./gradlew :fence:bootJar");
                        System.err.println();
                        System.err.println("Then install it:");
                        System.err.println("  mkdir -p " + fenceJar.getParent());
                        System.err.println("  cp fence/build/libs/fence-*.jar " + fenceJar);
                        return 1;
                    }
                } else {
                    System.out.println("Skipping gateway: it requires the fence auth server (use without --no-auth to enable it).");
                }

                if (shouldStartGateway()) {
                    Path gatewayJar = gatewayBinary();
                    if (!Files.isRegularFile(gatewayJar) || !Files.isReadable(gatewayJar)) {
                        System.err.println("Gateway JAR not found at: " + gatewayJar);
                        System.err.println();
                        System.err.println("Build it from source with:");
                        System.err.println("  ./gradlew :gateway:bootJar");
                        System.err.println();
                        System.err.println("Then install it:");
                        System.err.println("  mkdir -p " + gatewayJar.getParent());
                        System.err.println("  cp gateway/build/libs/gateway-*.jar " + gatewayJar);
                        return 1;
                    }
                }

                // ADJ-1: resolve UI binary BEFORE launching anything (fail fast -> nothing started)
                Path uiBinary = null;
                if (!noUi && !foreground) {
                    try {
                        String v = uiVersion != null && !uiVersion.isBlank() ? uiVersion
                                 : dev.orchard.trowel.devserver.UiBackendResolver.DEFAULT_UI_VERSION;
                        uiBinary = new dev.orchard.trowel.devserver.UiBackendResolver(uiBackendBinary(), v).resolve();
                    } catch (dev.orchard.trowel.devserver.UiBackendUnavailableException e) {
                        System.err.println(e.getMessage());
                        return 1;   // nothing launched
                    }
                }

                // ADJ-3: foreground can't run the UI (it blocks on core inheritIO)
                if (foreground && !noUi) {
                    System.out.println("foreground mode runs core only (:" + corePort + "); omit -f to also run the UI");
                }

                // Start fence before core when auth is enabled
                Process fenceProcess = null;
                if (!noAuth) {
                    fenceProcess = startFence();
                    if (fenceProcess == null) {
                        return 1;
                    }
                }

                var command = buildCommand(coreBinary);

                if (foreground) {
                    return runForeground(command, fenceProcess);
                } else {
                    return runBackground(command, uiBinary, fenceProcess);
                }

            } catch (Exception e) {
                System.err.println("Failed to start dev-server: " + e.getMessage());
                return 1;
            }
        }

        ArrayList<String> buildCommand(Path binary) {
            var command = new ArrayList<String>();
            command.add(binary.toString());
            command.add("--spring.profiles.active=devserver");
            command.add("--server.port=" + corePort);

            String cultivatorId = resolveCultivatorId();
            if (cultivatorId != null && !cultivatorId.isBlank()) {
                command.add("--orchard.dev.default-cultivator-id=" + cultivatorId);
            }

            if (verbose) {
                command.add("--logging.level.dev.orchard=DEBUG");
            }

            if (noAuth) {
                command.add("--orchard.security.oauth2.enabled=false");
            } else {
                command.add("--spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:" + fencePort);
            }

            return command;
        }

        ArrayList<String> buildUiCommand(Path uiBinary) {
            var command = new ArrayList<String>();
            command.add(uiBinary.toString());
            command.add("--server.port=" + port);
            return command;
        }

        java.util.Map<String, String> uiEnv() {
            return java.util.Map.of("ORCHARD_CORE_BASE_URL", "http://localhost:" + corePort);
        }

        private int runForeground(ArrayList<String> command, Process fenceProcess) throws IOException, InterruptedException {
            System.out.println("\u001B[1;32mStarting Orchard dev-server (foreground)...\u001B[0m");
            System.out.println("  Port: " + corePort);
            if (fenceProcess != null) {
                System.out.println("  Fence: http://localhost:" + fencePort);
            }
            System.out.println("  Press Ctrl+C to stop");
            System.out.println();

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();

            // Foreground mode has no health-check gate on core, so best-effort start the
            // gateway right away (it lazily contacts fence/trellis on first SSH session).
            Process gatewayProcess = null;
            if (shouldStartGateway()) {
                gatewayProcess = startGateway();
            }

            Process fenceFinal = fenceProcess;
            Process gatewayFinal = gatewayProcess;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process.isAlive()) {
                    process.destroy();
                }
                if (fenceFinal != null && fenceFinal.isAlive()) {
                    fenceFinal.destroy();
                }
                if (gatewayFinal != null && gatewayFinal.isAlive()) {
                    gatewayFinal.destroy();
                }
            }));

            int exitCode = process.waitFor();

            if (fenceFinal != null && fenceFinal.isAlive()) {
                fenceFinal.destroy();
                fenceFinal.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                Files.deleteIfExists(fencePidFile());
            }
            if (gatewayFinal != null && gatewayFinal.isAlive()) {
                gatewayFinal.destroy();
                gatewayFinal.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                Files.deleteIfExists(gatewayPidFile());
            }
            return exitCode;
        }

        private int runBackground(ArrayList<String> command, Path uiBinary, Process fenceProcess) throws IOException, InterruptedException {
            System.out.println("\u001B[1;32mStarting Orchard dev-server...\u001B[0m");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            long pid = process.pid();
            Files.writeString(pidFile(), pid + "\n" + corePort);

            System.out.println("  Core PID: " + pid);
            System.out.println("  Core logs: " + logFile());

            // Wait for health check
            System.out.print("  Waiting for server to start");
            boolean coreHealthy = waitForHealth(process, corePort, "/api/health", 30);

            if (!coreHealthy) {
                process.destroy();
                Files.deleteIfExists(pidFile());
                if (fenceProcess != null && fenceProcess.isAlive()) {
                    fenceProcess.destroy();
                    Files.deleteIfExists(fencePidFile());
                }
                if (!process.isAlive()) {
                    System.out.println(" exited.");
                    System.err.println("orchard core exited on startup - check " + logFile());
                } else {
                    System.out.println(" timed out.");
                    System.err.println("orchard core did not become healthy in time - check " + logFile());
                }
                return 1;
            }

            System.out.println(" ready!");

            // Gateway needs trellis (core) + fence up, so it only starts once core is healthy.
            Process gatewayProcess = null;
            if (shouldStartGateway()) {
                gatewayProcess = startGateway();
                if (gatewayProcess == null) {
                    process.destroy();
                    Files.deleteIfExists(pidFile());
                    if (fenceProcess != null && fenceProcess.isAlive()) {
                        fenceProcess.destroy();
                        Files.deleteIfExists(fencePidFile());
                    }
                    return 1;
                }
            }

            if (uiBinary != null) {   // i.e. !noUi && !foreground
                int r = startUiBackend(uiBinary, process);
                if (r != 0) {
                    if (fenceProcess != null && fenceProcess.isAlive()) {
                        fenceProcess.destroy();
                        Files.deleteIfExists(fencePidFile());
                    }
                    if (gatewayProcess != null && gatewayProcess.isAlive()) {
                        gatewayProcess.destroy();
                        Files.deleteIfExists(gatewayPidFile());
                    }
                    return r;
                }
                printConnectionInfo(port);   // BFF URL is what to open
            } else {
                printCoreOnlyInfo(corePort);
            }
            return 0;
        }

        // ADJ-2: package-visible, gains Process param and bails if it died
        boolean waitForHealth(Process process, int port, String healthPath, int timeoutSeconds) {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

            while (System.currentTimeMillis() < deadline) {
                // ADJ-2: bail fast if the process already died
                if (process != null && !process.isAlive()) {
                    System.out.println(" exited.");
                    return false;
                }
                try {
                    Thread.sleep(1000);
                    System.out.print(".");

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + healthPath))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 400) {
                        return true;
                    }
                } catch (Exception e) {
                    // Server not ready yet
                }
            }
            return false;
        }

        private int startUiBackend(Path uiBinary, Process coreProcess) throws IOException, InterruptedException {
            System.out.println("\u001B[1;32mStarting orchard-ui...\u001B[0m");
            ProcessBuilder pb = new ProcessBuilder(buildUiCommand(uiBinary));
            pb.environment().putAll(uiEnv());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(uiLogFile().toFile()));
            pb.redirectErrorStream(true);
            Process ui = pb.start();
            Files.writeString(uiPidFile(), ui.pid() + "\n" + port);

            System.out.print("  Waiting for UI to start");
            boolean uiHealthy = waitForHealth(ui, port, "/actuator/health", 30);
            if (!uiHealthy) {
                ui.destroy();
                Files.deleteIfExists(uiPidFile());
                coreProcess.destroy();
                Files.deleteIfExists(pidFile());
                if (!ui.isAlive()) {
                    System.out.println(" exited.");
                    System.err.println("orchard-ui exited on startup - check " + uiLogFile());
                } else {
                    System.out.println(" timed out.");
                    System.err.println("orchard-ui did not become healthy in time - check " + uiLogFile());
                }
                return 1;
            }
            System.out.println(" ready!");

            // ADJ-4: optional browser open
            if (open) {
                String url = "http://localhost:" + port;
                String os = System.getProperty("os.name").toLowerCase();
                String opener = os.contains("mac") || os.contains("darwin") ? "open" : "xdg-open";
                try { new ProcessBuilder(opener, url).start(); }
                catch (IOException ex) { System.out.println("  (could not auto-open browser: " + ex.getMessage() + ")"); }
            }
            return 0;
        }

        // Fence's gateway-client secret MUST match the gateway's oauth2.client-secret
        // (see buildGatewayCommand) or the gateway's client_credentials login to fence fails.
        ArrayList<String> buildFenceCommand(Path fenceJar) {
            var command = new ArrayList<String>();
            command.add("java");
            command.add("-jar");
            command.add(fenceJar.toString());
            command.add("--server.port=" + fencePort);
            command.add("--spring.profiles.active=standalone");
            command.add("--fence.gateway-client.client-secret=" + DEV_GATEWAY_CLIENT_SECRET);
            return command;
        }

        private Process startFence() throws IOException {
            var fenceJar = fenceBinary();
            System.out.println("  Fence: starting on port " + fencePort);

            ProcessBuilder pb = new ProcessBuilder(buildFenceCommand(fenceJar));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(fenceLogFile().toFile()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Files.writeString(fencePidFile(), process.pid() + "\n" + fencePort);

            System.out.println("    PID: " + process.pid());
            System.out.println("    Logs: " + fenceLogFile());

            System.out.print("  Waiting for fence to start");
            boolean healthy = waitForHealth(process, fencePort, "/actuator/health", 15);
            if (!healthy) {
                process.destroy();
                Files.deleteIfExists(fencePidFile());
                if (!process.isAlive()) {
                    System.out.println(" exited.");
                    System.err.println("fence exited on startup - check " + fenceLogFile());
                } else {
                    System.out.println(" timed out.");
                    System.err.println("fence did not become healthy in time - check " + fenceLogFile());
                }
                return null;
            }
            System.out.println(" ready!");
            return process;
        }

        // Gateway's ssh-port is what cultivators connect to; its own admin/actuator
        // endpoint (health checks) lives on GATEWAY_ADMIN_PORT (server.port), separate
        // from the ssh-port. internal-ssh-key-path MUST match the key trellis/QEMU
        // authorizes on seedlings (see DevServerCommand.internalSshKeyPath()), and
        // oauth2.client-secret MUST match fence's gateway-client secret (buildFenceCommand).
        ArrayList<String> buildGatewayCommand(Path gatewayJar) {
            var command = new ArrayList<String>();
            command.add("java");
            command.add("-jar");
            command.add(gatewayJar.toString());
            command.add("--orchard.gateway.ssh-port=" + gatewaySshPort);
            command.add("--orchard.gateway.internal-ssh-key-path=" + internalSshKeyPath());
            command.add("--orchard.gateway.fence.issuer-uri=http://localhost:" + fencePort);
            command.add("--orchard.gateway.trellis.base-url=http://localhost:" + corePort);
            command.add("--orchard.gateway.oauth2.client-secret=" + DEV_GATEWAY_CLIENT_SECRET);
            return command;
        }

        private Process startGateway() throws IOException {
            var gatewayJar = gatewayBinary();
            System.out.println("  Gateway: starting on SSH port " + gatewaySshPort);

            ProcessBuilder pb = new ProcessBuilder(buildGatewayCommand(gatewayJar));
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(gatewayLogFile().toFile()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Files.writeString(gatewayPidFile(), process.pid() + "\n" + gatewaySshPort);

            System.out.println("    PID: " + process.pid());
            System.out.println("    Logs: " + gatewayLogFile());

            System.out.print("  Waiting for gateway to start");
            boolean healthy = waitForHealth(process, GATEWAY_ADMIN_PORT, "/actuator/health", 15);
            if (!healthy) {
                process.destroy();
                Files.deleteIfExists(gatewayPidFile());
                if (!process.isAlive()) {
                    System.out.println(" exited.");
                    System.err.println("gateway exited on startup - check " + gatewayLogFile());
                } else {
                    System.out.println(" timed out.");
                    System.err.println("gateway did not become healthy in time - check " + gatewayLogFile());
                }
                return null;
            }
            System.out.println(" ready!");
            return process;
        }
    }

    @Command(name = "stop", description = "Stop the local Orchard development server")
    public static class Stop implements Callable<Integer> {

        @ParentCommand
        DevServerCommand parent;

        private void stopOne(Path pidFile, String label) throws IOException {
            if (!Files.exists(pidFile)) {
                return;
            }
            try {
                long processId = Long.parseLong(Files.readAllLines(pidFile).getFirst().trim());
                ProcessHandle.of(processId).ifPresentOrElse(handle -> {
                    System.out.println("Stopping " + label + " (PID: " + processId + ")...");
                    handle.destroy();
                    boolean exited = handle.onExit()
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .handle((result, ex) -> ex == null)
                        .join();
                    if (!exited) {
                        System.err.println(label + " did not stop gracefully, force killing...");
                        handle.destroyForcibly();
                    }
                }, () -> System.out.println(label + " process " + processId + " not running (stale PID file)."));
            } catch (NumberFormatException ignored) {
                // unreadable pid file; fall through to deletion
            }
            Files.deleteIfExists(pidFile);
        }

        @Override
        public Integer call() {
            try {
                if (!Files.exists(pidFile()) && !Files.exists(uiPidFile())
                        && !Files.exists(fencePidFile()) && !Files.exists(gatewayPidFile())) {
                    System.out.println("Orchard dev-server is not running (no PID file found).");
                    return 0;
                }
                stopOne(gatewayPidFile(), "orchard gateway"); // consumer of core+fence, stop first
                stopOne(uiPidFile(), "orchard-ui");       // proxy first
                stopOne(pidFile(), "orchard core");       // then upstream
                stopOne(fencePidFile(), "orchard fence"); // fence last (core depends on it)
                System.out.println("\u001B[1;32mOrchard dev-server stopped.\u001B[0m");
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to stop dev-server: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "status", description = "Check the status of the local Orchard development server")
    public static class Status implements Callable<Integer> {

        @ParentCommand
        DevServerCommand parent;

        @Override
        public Integer call() {
            try {
                ServerInfo info = readServerInfo();
                if (info == null) {
                    System.out.println("Orchard dev-server: \u001B[31mstopped\u001B[0m");
                    return 0;
                }

                boolean processAlive = ProcessHandle.of(info.pid())
                    .map(ProcessHandle::isAlive)
                    .orElse(false);

                if (!processAlive) {
                    System.out.println("Orchard dev-server: \u001B[31mstopped\u001B[0m (stale PID file)");
                    Files.deleteIfExists(pidFile());
                    return 0;
                }

                System.out.println("Orchard dev-server: \u001B[1;32mrunning\u001B[0m");
                System.out.println("  PID: " + info.pid());

                // Try health endpoint to get more info
                try {
                    HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + info.port() + "/api/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 400) {
                        System.out.println("  URL: http://localhost:" + info.port());
                        System.out.println("  Health: UP");
                    }
                } catch (Exception e) {
                    System.out.println("  Health: starting or unreachable");
                }

                System.out.println("  Logs: " + logFile());

                ServerInfo ui = readUiInfo();
                if (ui != null && ProcessHandle.of(ui.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                    System.out.println("  UI PID: " + ui.pid());
                    System.out.println("  UI URL: http://localhost:" + ui.port());
                } else if (ui != null) {
                    Files.deleteIfExists(uiPidFile());
                }

                ServerInfo fence = readFenceInfo();
                if (fence != null && ProcessHandle.of(fence.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                    System.out.println("  Fence PID: " + fence.pid());
                    System.out.println("  Fence URL: http://localhost:" + fence.port());
                } else if (fence != null) {
                    Files.deleteIfExists(fencePidFile());
                }

                ServerInfo gateway = readGatewayInfo();
                if (gateway != null && ProcessHandle.of(gateway.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                    System.out.println("  Gateway PID: " + gateway.pid());
                    System.out.println("  Gateway SSH port: " + gateway.port());
                } else if (gateway != null) {
                    Files.deleteIfExists(gatewayPidFile());
                }
                return 0;

            } catch (Exception e) {
                System.err.println("Failed to check status: " + e.getMessage());
                return 1;
            }
        }
    }

    record ServerInfo(long pid, int port) {}

    static ServerInfo readInfo(Path pidFile, int defaultPort) {
        if (!Files.exists(pidFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(pidFile);
            long pid = Long.parseLong(lines.getFirst().trim());
            int port = lines.size() > 1 ? Integer.parseInt(lines.get(1).trim()) : defaultPort;
            return new ServerInfo(pid, port);
        } catch (Exception e) { return null; }
    }

    static ServerInfo readServerInfo() { return readInfo(pidFile(), 7778); }

    static ServerInfo readUiInfo()     { return readInfo(uiPidFile(), 7777); }

    static ServerInfo readFenceInfo()  { return readInfo(fencePidFile(), 7779); }

    static ServerInfo readGatewayInfo() { return readInfo(gatewayPidFile(), 2222); }

    private static boolean isServerRunning() {
        ServerInfo info = readServerInfo();
        if (info == null) {
            return false;
        }
        return ProcessHandle.of(info.pid())
            .map(ProcessHandle::isAlive)
            .orElse(false);
    }

    private static void ensureDirectories() throws IOException {
        Files.createDirectories(orchardHome().resolve("bin"));
        Files.createDirectories(orchardHome().resolve("run"));
        Files.createDirectories(orchardHome().resolve("logs"));
        Files.createDirectories(orchardHome().resolve("data"));
    }

    private static void printConnectionInfo(int port) {
        System.out.println();
        System.out.println("  \u001B[1mOrchard dev-server\u001B[0m");
        System.out.println("  UI:   http://localhost:" + port);
        System.out.println("  (API is proxied through the UI at /api)");
        System.out.println();
        System.out.println("  Use 'trowel grove list' to get started.");
        System.out.println("  Use 'trowel dev-server stop' to shut down.");
    }

    private static void printCoreOnlyInfo(int corePort) {
        System.out.println();
        System.out.println("  \u001B[1mOrchard dev-server (core only)\u001B[0m");
        System.out.println("  API:  http://localhost:" + corePort + "/api");
        System.out.println();
        System.out.println("  Use 'trowel dev-server stop' to shut down.");
    }
}
