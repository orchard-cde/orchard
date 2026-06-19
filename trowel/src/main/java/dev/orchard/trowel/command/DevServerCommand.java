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

                Path binary = serverBinary();
                if (!Files.isExecutable(binary)) {
                    System.err.println("Orchard server binary not found at: " + binary);
                    System.err.println();
                    System.err.println("Build it from source with:");
                    System.err.println("  ./gradlew :trellis:nativeCompile");
                    System.err.println();
                    System.err.println("Then install it:");
                    System.err.println("  mkdir -p " + binary.getParent());
                    System.err.println("  cp trellis/build/native/nativeCompile/orchard-server " + binary);
                    return 1;
                }

                ensureDirectories();

                var command = buildCommand(binary);

                if (foreground) {
                    return runForeground(command);
                } else {
                    return runBackground(command);
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

        private int runForeground(ArrayList<String> command) throws IOException, InterruptedException {
            System.out.println("\u001B[1;32mStarting Orchard dev-server (foreground)...\u001B[0m");
            System.out.println("  Port: " + port);
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

        private int runBackground(ArrayList<String> command) throws IOException, InterruptedException {
            System.out.println("\u001B[1;32mStarting Orchard dev-server...\u001B[0m");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            long pid = process.pid();
            Files.writeString(pidFile(), pid + "\n" + port);

            System.out.println("  PID: " + pid);
            System.out.println("  Logs: " + logFile());

            // Wait for health check
            System.out.print("  Waiting for server to start");
            boolean healthy = waitForHealth(port, 30);

            if (healthy) {
                System.out.println(" ready!");
                printConnectionInfo(port);
                return 0;
            } else {
                System.out.println(" timed out.");
                System.err.println("Server may still be starting. Check logs: " + logFile());
                return 1;
            }
        }

        private boolean waitForHealth(int port, int timeoutSeconds) {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000);
                    System.out.print(".");

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/health"))
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
                            System.out.println("\u001B[1;32mOrchard dev-server stopped.\u001B[0m");
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
        System.out.println("  \u001B[1mOrchard dev-server\u001B[0m");
        System.out.println("  URL:  http://localhost:" + port);
        System.out.println("  API:  http://localhost:" + port + "/api");
        System.out.println();
        System.out.println("  Use 'trowel grove list' to get started.");
        System.out.println("  Use 'trowel dev-server stop' to shut down.");
    }
}
