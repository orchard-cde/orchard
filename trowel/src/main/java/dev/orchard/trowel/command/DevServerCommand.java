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
            return (parent != null && parent.parent != null) ? parent.parent.getCultivatorId() : null;
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

        // Test seam: override the core port without picocli parsing.
        void setCorePortForTest(int p) { this.corePort = p; }

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

                // ADJ-1: resolve UI binary BEFORE launching core (fail fast → nothing started)
                Path uiBinary = null;
                if (!noUi && !foreground) {
                    try {
                        String v = (uiVersion != null && !uiVersion.isBlank()) ? uiVersion
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

                var command = buildCommand(coreBinary);

                if (foreground) {
                    return runForeground(command);
                } else {
                    return runBackground(command, uiBinary);
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

        private int runForeground(ArrayList<String> command) throws IOException, InterruptedException {
            System.out.println("[1;32mStarting Orchard dev-server (foreground)...[0m");
            System.out.println("  Port: " + corePort);
            System.out.println("  Press Ctrl+C to stop");
            System.out.println();

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO();
            Process process = pb.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process.isAlive()) {
                    process.destroy();
                }
            }));

            return process.waitFor();
        }

        private int runBackground(ArrayList<String> command, Path uiBinary) throws IOException, InterruptedException {
            System.out.println("[1;32mStarting Orchard dev-server...[0m");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            long pid = process.pid();
            Files.writeString(pidFile(), pid + "\n" + corePort);

            System.out.println("  PID: " + pid);
            System.out.println("  Logs: " + logFile());

            // Wait for health check
            System.out.print("  Waiting for server to start");
            boolean coreHealthy = waitForHealth(process, corePort, "/api/health", 30);

            if (!coreHealthy) {
                System.out.println(" timed out.");
                System.err.println("Server may still be starting. Check logs: " + logFile());
                return 1;
            }

            System.out.println(" ready!");
            if (uiBinary != null) {   // i.e. !noUi && !foreground
                int r = startUiBackend(uiBinary);
                if (r != 0) return r;
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

        private int startUiBackend(Path uiBinary) throws IOException, InterruptedException {
            System.out.println("[1;32mStarting orchard-ui...[0m");
            ProcessBuilder pb = new ProcessBuilder(buildUiCommand(uiBinary));
            pb.environment().putAll(uiEnv());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(uiLogFile().toFile()));
            pb.redirectErrorStream(true);
            Process ui = pb.start();
            Files.writeString(uiPidFile(), ui.pid() + "\n" + port);

            System.out.print("  Waiting for UI to start");
            boolean uiHealthy = waitForHealth(ui, port, "/actuator/health", 30);
            if (!uiHealthy) {
                System.out.println(" timed out.");
                System.err.println("UI may still be starting. Check logs: " + uiLogFile());
                return 1;
            }
            System.out.println(" ready!");

            // ADJ-4: optional browser open
            if (open) {
                String url = "http://localhost:" + port;
                String os = System.getProperty("os.name").toLowerCase();
                String opener = (os.contains("mac") || os.contains("darwin")) ? "open" : "xdg-open";
                try { new ProcessBuilder(opener, url).start(); }
                catch (IOException ex) { System.out.println("  (could not auto-open browser: " + ex.getMessage() + ")"); }
            }
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop the local Orchard development server")
    public static class Stop implements Callable<Integer> {

        @ParentCommand
        DevServerCommand parent;

        @Override
        public Integer call() {
            try {
                ServerInfo info = readServerInfo();
                if (info == null) {
                    System.out.println("Orchard dev-server is not running (no PID file found).");
                    return 0;
                }

                long processId = info.pid();

                ProcessHandle.of(processId).ifPresentOrElse(
                    handle -> {
                        System.out.println("Stopping Orchard dev-server (PID: " + processId + ")...");
                        handle.destroy();

                        // Wait for process to exit
                        boolean exited = handle.onExit()
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .handle((result, ex) -> ex == null)
                            .join();

                        if (exited) {
                            System.out.println("[1;32mOrchard dev-server stopped.[0m");
                        } else {
                            System.err.println("Server did not stop gracefully, force killing...");
                            handle.destroyForcibly();
                        }
                    },
                    () -> System.out.println("Process " + processId + " is not running (stale PID file).")
                );

                Files.deleteIfExists(pidFile());
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
                    System.out.println("Orchard dev-server: [31mstopped[0m");
                    return 0;
                }

                boolean processAlive = ProcessHandle.of(info.pid())
                    .map(ProcessHandle::isAlive)
                    .orElse(false);

                if (!processAlive) {
                    System.out.println("Orchard dev-server: [31mstopped[0m (stale PID file)");
                    Files.deleteIfExists(pidFile());
                    return 0;
                }

                System.out.println("Orchard dev-server: [1;32mrunning[0m");
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
                return 0;

            } catch (Exception e) {
                System.err.println("Failed to check status: " + e.getMessage());
                return 1;
            }
        }
    }

    record ServerInfo(long pid, int port) {}

    static ServerInfo readServerInfo() {
        Path pid = pidFile();
        if (!Files.exists(pid)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(pid);
            long processId = Long.parseLong(lines.getFirst().trim());
            int port = lines.size() > 1 ? Integer.parseInt(lines.get(1).trim()) : 7778;
            return new ServerInfo(processId, port);
        } catch (Exception e) {
            return null;
        }
    }

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
        System.out.println("  [1mOrchard dev-server[0m");
        System.out.println("  UI:   http://localhost:" + port);
        System.out.println("  (API is proxied through the UI at /api)");
        System.out.println();
        System.out.println("  Use 'trowel grove list' to get started.");
        System.out.println("  Use 'trowel dev-server stop' to shut down.");
    }

    private static void printCoreOnlyInfo(int corePort) {
        System.out.println();
        System.out.println("  [1mOrchard dev-server (core only)[0m");
        System.out.println("  API:  http://localhost:" + corePort + "/api");
        System.out.println();
        System.out.println("  Use 'trowel dev-server stop' to shut down.");
    }
}
