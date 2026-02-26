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

    @Override
    public Integer call() {
        picocli.CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "start", description = "Start the local Orchard development server")
    public static class Start implements Callable<Integer> {

        @ParentCommand
        DevServerCommand parent;

        @Option(names = {"--foreground", "-f"}, description = "Run in foreground (default: background)")
        boolean foreground;

        @Option(names = {"--verbose", "-v"}, description = "Enable debug logging")
        boolean verbose;

        @Option(names = {"--port", "-p"}, description = "Server port (default: 8080)", defaultValue = "8080")
        int port;

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

        private ArrayList<String> buildCommand(Path binary) {
            var command = new ArrayList<String>();
            command.add(binary.toString());
            command.add("--spring.profiles.active=devserver");
            command.add("--server.port=" + port);

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
            Files.writeString(pidFile(), String.valueOf(pid));

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
                Path pid = pidFile();
                if (!Files.exists(pid)) {
                    System.out.println("Orchard dev-server is not running (no PID file found).");
                    return 0;
                }

                long processId = Long.parseLong(Files.readString(pid).trim());

                ProcessHandle.of(processId).ifPresentOrElse(
                    handle -> {
                        System.out.println("Stopping Orchard dev-server (PID: " + processId + ")...");
                        handle.destroy();

                        // Wait for process to exit
                        boolean exited = handle.onExit()
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .handle((ph, ex) -> ex == null)
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

                Files.deleteIfExists(pid);
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
                Path pid = pidFile();
                if (!Files.exists(pid)) {
                    System.out.println("Orchard dev-server: \u001B[31mstopped\u001B[0m");
                    return 0;
                }

                long processId = Long.parseLong(Files.readString(pid).trim());
                boolean processAlive = ProcessHandle.of(processId)
                    .map(ProcessHandle::isAlive)
                    .orElse(false);

                if (!processAlive) {
                    System.out.println("Orchard dev-server: \u001B[31mstopped\u001B[0m (stale PID file)");
                    Files.deleteIfExists(pid);
                    return 0;
                }

                System.out.println("Orchard dev-server: \u001B[1;32mrunning\u001B[0m");
                System.out.println("  PID: " + processId);

                // Try health endpoint to get more info
                try {
                    HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build();

                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:8080/api/health"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 400) {
                        System.out.println("  URL: http://localhost:8080");
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

    private static boolean isServerRunning() {
        Path pid = pidFile();
        if (!Files.exists(pid)) return false;
        try {
            long processId = Long.parseLong(Files.readString(pid).trim());
            return ProcessHandle.of(processId)
                .map(ProcessHandle::isAlive)
                .orElse(false);
        } catch (Exception e) {
            return false;
        }
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
