package dev.orchard.trowel.auth;

import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * AuthProvider that uses a stored access token, silently refreshing when expired.
 * Reads config from disk on every call to stay fresh.
 */
public class TokenAuthProvider implements AuthProvider {

    private static final long EXPIRY_SKEW_SECONDS = 30;

    private final String accessToken;
    private final String refreshToken;
    private final long expiresAt;
    private final String fenceServerUrl;
    private final String clientId;

    public TokenAuthProvider(String accessToken, String refreshToken, long expiresAt,
                             String fenceServerUrl, String clientId) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.fenceServerUrl = fenceServerUrl;
        this.clientId = clientId;
    }

    @Override
    public String authorizationHeader() throws IOException, InterruptedException {
        long now = System.currentTimeMillis() / 1000;
        if (now < expiresAt - EXPIRY_SKEW_SECONDS) {
            return "Bearer " + accessToken;
        }

        // Token is expired or near-expiry — refresh
        FenceClient fenceClient = createFenceClient();
        var tokenResponse = fenceClient.refreshToken(refreshToken);

        // Persist refreshed tokens back to config
        persistRefreshedTokens(tokenResponse);

        return "Bearer " + tokenResponse.accessToken();
    }

    FenceClient createFenceClient() {
        return new FenceClient(fenceServerUrl, clientId);
    }

    private void persistRefreshedTokens(FenceClient.TokenResponse tokenResponse) throws IOException {
        OrchardConfig config = ConfigLoader.load();
        if (config == null) return;

        String activeTargetName = config.active();
        if (activeTargetName == null) return;

        var targets = new LinkedHashMap<>(config.targets());
        var oldTarget = targets.get(activeTargetName);
        if (oldTarget == null) return;

        long newExpiresAt = (System.currentTimeMillis() / 1000) + tokenResponse.expiresIn();
        targets.put(activeTargetName, new OrchardConfig.Target(
            oldTarget.server(),
            oldTarget.cultivator(),
            oldTarget.fenceServer(),
            tokenResponse.accessToken(),
            tokenResponse.refreshToken(),
            newExpiresAt
        ));

        ConfigLoader.save(new OrchardConfig(config.active(), targets));
    }
}
