package dev.orchard.trowel.auth;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FenceClientTest {

    static HttpServer server;
    static String baseUrl;
    static final ObjectMapper mapper = JsonMapper.builder().build();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @AfterEach
    void removeContexts() {
        try { server.removeContext("/oauth2/device_authorization"); } catch (IllegalArgumentException ignored) {}
        try { server.removeContext("/oauth2/token"); } catch (IllegalArgumentException ignored) {}
    }

    @Test
    void requestDeviceAuthorization_parsesResponse() throws Exception {
        server.createContext("/oauth2/device_authorization", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "device_code", "dc-123",
                "user_code", "ABCD-EFGH",
                "verification_uri", "https://fence.example.com/device",
                "expires_in", 600,
                "interval", 5
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        var response = client.requestDeviceAuthorization();

        assertThat(response.deviceCode()).isEqualTo("dc-123");
        assertThat(response.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(response.verificationUri()).isEqualTo("https://fence.example.com/device");
        assertThat(response.expiresIn()).isEqualTo(600);
        assertThat(response.interval()).isEqualTo(5);
    }

    @Test
    void requestDeviceAuthorization_sendsCorrectBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/oauth2/device_authorization", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "device_code", "dc-123",
                "user_code", "ABCD-EFGH",
                "verification_uri", "https://fence.example.com/device",
                "expires_in", 600,
                "interval", 5
            )));
        });

        var client = new FenceClient(baseUrl, "my-client-id");
        client.requestDeviceAuthorization();

        assertThat(capturedBody.get()).contains("client_id=my-client-id");
    }

    @Test
    void pollDeviceToken_pending_throwsAuthorizationPendingException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "error", "authorization_pending"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.pollDeviceToken("dc-123"))
            .isInstanceOf(AuthorizationPendingException.class);
    }

    @Test
    void pollDeviceToken_slowDown_throwsSlowDownException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "error", "slow_down"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.pollDeviceToken("dc-123"))
            .isInstanceOf(SlowDownException.class);
    }

    @Test
    void pollDeviceToken_expiredToken_throwsDeviceCodeExpiredException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "error", "expired_token"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.pollDeviceToken("dc-123"))
            .isInstanceOf(DeviceCodeExpiredException.class);
    }

    @Test
    void pollDeviceToken_accessDenied_throwsAuthorizationDeniedException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "error", "access_denied"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.pollDeviceToken("dc-123"))
            .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void pollDeviceToken_invalidGrant_throwsInvalidGrantException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "error", "invalid_grant"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.pollDeviceToken("dc-123"))
            .isInstanceOf(InvalidGrantException.class);
    }

    @Test
    void pollDeviceToken_success_returnsTokenResponse() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "access_token", "at-xyz",
                "refresh_token", "rt-xyz",
                "token_type", "Bearer",
                "expires_in", 3600
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        var response = client.pollDeviceToken("dc-123");

        assertThat(response.accessToken()).isEqualTo("at-xyz");
        assertThat(response.refreshToken()).isEqualTo("rt-xyz");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
    }

    @Test
    void pollDeviceToken_sendsCorrectGrantTypeAndDeviceCode() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/oauth2/token", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "access_token", "at-xyz",
                "refresh_token", "rt-xyz",
                "token_type", "Bearer",
                "expires_in", 3600
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        client.pollDeviceToken("dc-abc");

        assertThat(capturedBody.get()).contains("grant_type=urn");
        assertThat(capturedBody.get()).contains("device_code=dc-abc");
        assertThat(capturedBody.get()).contains("client_id=test-client-id");
    }

    @Test
    void refreshToken_success_returnsTokenResponse() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "access_token", "at-refreshed",
                "refresh_token", "rt-new",
                "token_type", "Bearer",
                "expires_in", 3600
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        var response = client.refreshToken("rt-old");

        assertThat(response.accessToken()).isEqualTo("at-refreshed");
        assertThat(response.refreshToken()).isEqualTo("rt-new");
    }

    @Test
    void refreshToken_invalidGrant_throwsRefreshTokenInvalidException() throws Exception {
        server.createContext("/oauth2/token", exchange -> {
            respond(exchange, 401, mapper.writeValueAsString(Map.of(
                "error", "invalid_grant"
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.refreshToken("rt-bad"))
            .isInstanceOf(RefreshTokenInvalidException.class);
    }

    @Test
    void refreshToken_sendsCorrectBody() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/oauth2/token", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, mapper.writeValueAsString(Map.of(
                "access_token", "at-refreshed",
                "refresh_token", "rt-new",
                "token_type", "Bearer",
                "expires_in", 3600
            )));
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        client.refreshToken("rt-my-refresh");

        assertThat(capturedBody.get()).contains("grant_type=refresh_token");
        assertThat(capturedBody.get()).contains("refresh_token=rt-my-refresh");
        assertThat(capturedBody.get()).contains("client_id=test-client-id");
    }

    @Test
    void requestDeviceAuthorization_httpError_throwsFenceAuthException() throws Exception {
        server.createContext("/oauth2/device_authorization", exchange -> {
            respond(exchange, 500, "Internal Server Error");
        });

        var client = new FenceClient(baseUrl, "test-client-id");
        assertThatThrownBy(() -> client.requestDeviceAuthorization())
            .isInstanceOf(FenceAuthException.class)
            .hasMessageContaining("500");
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
