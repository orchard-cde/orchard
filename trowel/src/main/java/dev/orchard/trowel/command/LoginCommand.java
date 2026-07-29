package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.auth.AuthorizationPendingException;
import dev.orchard.trowel.auth.FenceClient;
import dev.orchard.trowel.auth.SlowDownException;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(
    name = "login",
    description = "Authenticate with Fence using device authorization grant"
)
public class LoginCommand implements Callable<Integer> {

    @Option(names = {"--fence-server"}, description = "Fence server URL (default: ${FENCE_SERVER_URL:-https://fence.orchard.dev})")
    private String fenceServerUrl;

    @Option(names = {"--no-browser"}, description = "Do not attempt to open browser")
    private boolean noBrowser;

    @Override
    public Integer call() {
        try {
            String fenceUrl = fenceServerUrl != null ? fenceServerUrl : System.getenv("FENCE_SERVER_URL");
            if (fenceUrl == null) {
                fenceUrl = Trowel.DEFAULT_FENCE_SERVER;
            }

            String clientId = Trowel.CLIENT_ID;

            FenceClient fenceClient = new FenceClient(fenceUrl, clientId);

            // Step 1: Request device authorization
            System.out.println();
            System.out.println("Requesting device authorization...");

            var deviceAuth = fenceClient.requestDeviceAuthorization();

            // Step 2: Display user code and verification URI
            System.out.println();
            System.out.println("Open this URL in your browser:");
            System.out.println();
            System.out.println("  " + deviceAuth.verificationUri());
            System.out.println();
            System.out.println("Enter this code when prompted:");
            System.out.println();
            System.out.println("  " + deviceAuth.userCode());
            System.out.println();
            System.out.println("Waiting for authorization...");

            if (!noBrowser) {
                try {
                    String osName = System.getProperty("os.name", "").toLowerCase();
                    ProcessBuilder pb;
                    if (osName.contains("mac") || osName.contains("darwin")) {
                        pb = new ProcessBuilder("open", deviceAuth.verificationUri());
                    } else if (osName.contains("win")) {
                        pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", deviceAuth.verificationUri());
                    } else {
                        pb = new ProcessBuilder("xdg-open", deviceAuth.verificationUri());
                    }
                    pb.redirectErrorStream(true);
                    pb.start();
                } catch (Exception ignored) {
                    // Browser not available, that's fine
                }
            }

            // Step 3: Poll for token
            var tokenResponse = pollForToken(fenceClient, deviceAuth.deviceCode(), deviceAuth.interval());

            // Step 4: Fetch cultivator info
            System.out.println();
            System.out.println("Authenticated! Fetching user info...");

            var orchardClient = new OrchardClient(getOrchardServerUrl(), () -> "Bearer " + tokenResponse.accessToken());
            var cultivator = orchardClient.getCurrentCultivator();

            // Step 5: Persist to config
            persistLoginResult(tokenResponse, fenceUrl, cultivator.id());

            String greetingName = cultivator.displayName() != null ? cultivator.displayName() : cultivator.email();

            System.out.println();
            System.out.println("Logged in as " + greetingName + " (" + cultivator.email() + ")");
            System.out.println();

            return 0;
        } catch (Exception e) {
            System.err.println("Login failed: " + e.getMessage());
            return 1;
        }
    }

    private FenceClient.TokenResponse pollForToken(FenceClient fenceClient, String deviceCode, int interval)
            throws Exception {
        long maxWaitMinutes = 10;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(maxWaitMinutes);
        int currentInterval = Math.max(interval, 5);

        while (System.nanoTime() < deadline) {
            try {
                return fenceClient.pollDeviceToken(deviceCode);
            } catch (AuthorizationPendingException e) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(currentInterval));
            } catch (SlowDownException e) {
                currentInterval += 5;
                Thread.sleep(TimeUnit.SECONDS.toMillis(currentInterval));
            }
        }

        throw new IOException("Login timed out after " + maxWaitMinutes + " minutes");
    }

    private void persistLoginResult(FenceClient.TokenResponse tokenResponse, String fenceUrl, String cultivatorId)
            throws IOException {
        OrchardConfig config = ConfigLoader.load();
        String activeName = config != null ? config.active() : "local";
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();

        if (config != null && config.targets() != null) {
            targets.putAll(config.targets());
        }

        OrchardConfig.Target existing = targets.get(activeName);
        String server = existing != null ? existing.server() : "http://localhost:7778";

        long expiresAt = (System.currentTimeMillis() / 1000) + tokenResponse.expiresIn();

        targets.put(activeName, new OrchardConfig.Target(
            server,
            cultivatorId,
            fenceUrl,
            tokenResponse.accessToken(),
            tokenResponse.refreshToken(),
            expiresAt
        ));

        ConfigLoader.save(new OrchardConfig(activeName, targets));
    }

    private String getOrchardServerUrl() {
        String env = System.getenv("ORCHARD_SERVER_URL");
        if (env != null) return env;

        OrchardConfig config = ConfigLoader.load();
        if (config != null) {
            OrchardConfig.Target target = config.activeTarget();
            if (target != null && target.server() != null) {
                return target.server();
            }
        }

        return "http://localhost:7778";
    }
}
