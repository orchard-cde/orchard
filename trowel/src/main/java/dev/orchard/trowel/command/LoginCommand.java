package dev.orchard.trowel.command;

import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.auth.AuthorizationPendingException;
import dev.orchard.trowel.auth.FenceClient;
import dev.orchard.trowel.auth.NoAuthProvider;
import dev.orchard.trowel.auth.SlowDownException;
import dev.orchard.trowel.client.OrchardClient;
import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
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

    @ParentCommand
    Trowel parent;

    @Option(names = {"--fence-server"}, description = "Fence server URL (default: $FENCE_SERVER_URL, else the issuer published by the Orchard server)")
    private String fenceServerUrl;

    @Option(names = {"--no-browser"}, description = "Do not attempt to open browser")
    private boolean noBrowser;

    static String resolveFenceServer(String flag, String env, String serverUrl) throws IOException {
        if (flag != null) {
            return flag;
        }
        if (env != null) {
            return env;
        }
        String reason = null;
        try {
            String issuer = new OrchardClient(serverUrl, new NoAuthProvider()).getAuthConfig().issuer();
            if (issuer != null && !issuer.isBlank()) {
                return issuer;
            }
        } catch (IOException e) {
            reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        }
        throw new IOException("server " + serverUrl
            + " publishes no fence issuer; pass --fence-server or set FENCE_SERVER_URL"
            + (reason != null ? " (" + reason + ")" : ""));
    }

    @Override
    public Integer call() {
        String fenceUrl;
        try {
            fenceUrl = resolveFenceServer(fenceServerUrl, System.getenv("FENCE_SERVER_URL"), parent.getServerUrl());
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        try {
            String clientId = Trowel.CLIENT_ID;

            FenceClient fenceClient = new FenceClient(fenceUrl, clientId);

            System.out.println();
            System.out.println("Requesting device authorization...");

            var deviceAuth = fenceClient.requestDeviceAuthorization();

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

            var tokenResponse = pollForToken(fenceClient, deviceAuth.deviceCode(), deviceAuth.pollIntervalSeconds());

            System.out.println();
            System.out.println("Authenticated! Fetching user info...");

            var orchardClient = new OrchardClient(parent.getServerUrl(), () -> "Bearer " + tokenResponse.accessToken());
            var cultivator = orchardClient.getCurrentCultivator();

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
        String explicitName = parent.getTargetName();
        String activeName = explicitName != null ? explicitName : config != null ? config.active() : "local";
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();

        if (config != null && config.targets() != null) {
            targets.putAll(config.targets());
        }

        OrchardConfig.Target existing = targets.get(activeName);
        String server = existing != null ? existing.server() : "http://localhost:17778";

        long expiresAt = (System.currentTimeMillis() / 1000) + tokenResponse.expiresIn();

        targets.put(activeName, new OrchardConfig.Target(
            server,
            cultivatorId,
            fenceUrl,
            tokenResponse.accessToken(),
            tokenResponse.refreshToken(),
            expiresAt,
            existing != null ? existing.sshKey() : null
        ));

        ConfigLoader.save(new OrchardConfig(activeName, targets));
    }
}
