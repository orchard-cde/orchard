package dev.orchard.trowel.auth;

import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenAuthProviderTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void authorizationHeader_returnsBearerToken() throws Exception {
        writeConfig("http://localhost:7779", "access-123", "refresh-abc", futureExpiry());

        var provider = new TokenAuthProvider("access-123", "refresh-abc", futureExpiry(), "http://localhost:7779", "test-client-id");
        assertThat(provider.authorizationHeader()).isEqualTo("Bearer access-123");
    }

    @Test
    void authorizationHeader_refreshesWhenExpired() throws Exception {
        writeConfig("http://localhost:7779", "old-access", "old-refresh", pastExpiry());

        var refreshCount = new AtomicInteger(0);
        FenceClient fenceClient = new FenceClient("http://localhost:7779", "test-client-id") {
            @Override
            public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
                refreshCount.incrementAndGet();
                return new TokenResponse("new-access", "new-refresh", "Bearer", 3600);
            }
        };

        var provider = new TokenAuthProvider("old-access", "old-refresh", pastExpiry(), "http://localhost:7779", "test-client-id") {
            @Override
            FenceClient createFenceClient() {
                return fenceClient;
            }
        };

        String header = provider.authorizationHeader();
        assertThat(header).isEqualTo("Bearer new-access");
        assertThat(refreshCount.get()).isEqualTo(1);
    }

    @Test
    void authorizationHeader_persistsRefreshedTokens() throws Exception {
        writeConfig("http://localhost:7779", "old-access", "old-refresh", pastExpiry());

        FenceClient fenceClient = new FenceClient("http://localhost:7779", "test-client-id") {
            @Override
            public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
                return new TokenResponse("refreshed-access", "refreshed-refresh", "Bearer", 3600);
            }
        };

        var provider = new TokenAuthProvider("old-access", "old-refresh", pastExpiry(), "http://localhost:7779", "test-client-id") {
            @Override
            FenceClient createFenceClient() {
                return fenceClient;
            }
        };

        provider.authorizationHeader();

        OrchardConfig reloaded = ConfigLoader.load();
        OrchardConfig.Target target = reloaded.targets().get("local");
        assertThat(target.accessToken()).isEqualTo("refreshed-access");
        assertThat(target.refreshToken()).isEqualTo("refreshed-refresh");
    }

    @Test
    void authorizationHeader_doesNotRefreshWhenTokenIsValid() throws Exception {
        writeConfig("http://localhost:7779", "valid-access", "valid-refresh", futureExpiry());

        var refreshCount = new AtomicInteger(0);
        FenceClient fenceClient = new FenceClient("http://localhost:7779", "test-client-id") {
            @Override
            public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
                refreshCount.incrementAndGet();
                return new TokenResponse("should-not-use", "should-not-use", "Bearer", 3600);
            }
        };

        var provider = new TokenAuthProvider("valid-access", "valid-refresh", futureExpiry(), "http://localhost:7779", "test-client-id") {
            @Override
            FenceClient createFenceClient() {
                return fenceClient;
            }
        };

        String header = provider.authorizationHeader();
        assertThat(header).isEqualTo("Bearer valid-access");
        assertThat(refreshCount.get()).isEqualTo(0);
    }

    @Test
    void authorizationHeader_throwsRefreshTokenInvalidException_whenRefreshFails() throws Exception {
        writeConfig("http://localhost:7779", "expired-access", "bad-refresh", pastExpiry());

        FenceClient fenceClient = new FenceClient("http://localhost:7779", "test-client-id") {
            @Override
            public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
                throw new RefreshTokenInvalidException();
            }
        };

        var provider = new TokenAuthProvider("expired-access", "bad-refresh", pastExpiry(), "http://localhost:7779", "test-client-id") {
            @Override
            FenceClient createFenceClient() {
                return fenceClient;
            }
        };

        assertThatThrownBy(() -> provider.authorizationHeader())
            .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void authorizationHeader_refreshesWhenNearExpiry() throws Exception {
        long nearExpiry = (System.currentTimeMillis() / 1000) + 10; // 10 seconds from now (within 30s skew)
        writeConfig("http://localhost:7779", "near-expiry-access", "near-expiry-refresh", nearExpiry);

        var refreshCount = new AtomicInteger(0);
        FenceClient fenceClient = new FenceClient("http://localhost:7779", "test-client-id") {
            @Override
            public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
                refreshCount.incrementAndGet();
                return new TokenResponse("fresh-access", "fresh-refresh", "Bearer", 3600);
            }
        };

        var provider = new TokenAuthProvider("near-expiry-access", "near-expiry-refresh", nearExpiry, "http://localhost:7779", "test-client-id") {
            @Override
            FenceClient createFenceClient() {
                return fenceClient;
            }
        };

        String header = provider.authorizationHeader();
        assertThat(header).isEqualTo("Bearer fresh-access");
        assertThat(refreshCount.get()).isEqualTo(1);
    }

    private void writeConfig(String fenceServer, String accessToken, String refreshToken, long expiresAt) throws IOException {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("local", new OrchardConfig.Target(
            "http://localhost:7778",
            "test-uuid",
            fenceServer,
            accessToken,
            refreshToken,
            expiresAt
        ));
        ConfigLoader.save(new OrchardConfig("local", targets));
    }

    private static long futureExpiry() {
        return (System.currentTimeMillis() / 1000) + 3600; // 1 hour from now
    }

    private static long pastExpiry() {
        return (System.currentTimeMillis() / 1000) - 3600; // 1 hour ago
    }
}
