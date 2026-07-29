package dev.orchard.trowel.command;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.config.ConfigLoader;
import dev.orchard.trowel.config.OrchardConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoginCommandTest {

    @TempDir
    Path tempDir;

    static HttpServer fenceServer;
    static int fencePort;

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalHome;

    @BeforeAll
    static void startFenceServer() throws IOException {
        fenceServer = HttpServer.create(new InetSocketAddress(0), 0);
        fenceServer.start();
        fencePort = fenceServer.getAddress().getPort();
    }

    @AfterAll
    static void stopFenceServer() {
        if (fenceServer != null) fenceServer.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));

        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());

        // Write initial config so getOrchardServerUrl() finds the right server
        Files.createDirectories(ConfigLoader.configDir());
        Files.writeString(ConfigLoader.tomlFile(), """
                active = "local"

                [targets.local]
                server = "http://localhost:%d"
                cultivator = "initial-uuid"
                """.formatted(fencePort));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setProperty("user.home", originalHome);
        try { fenceServer.removeContext("/oauth2/device_authorization"); } catch (Exception ignored) {}
        try { fenceServer.removeContext("/oauth2/token"); } catch (Exception ignored) {}
        try { fenceServer.removeContext("/api/me"); } catch (Exception ignored) {}
    }

    @Test
    void login_displaysUserCodeAndVerificationUri() throws Exception {
        fenceServer.createContext("/oauth2/device_authorization", exchange -> {
            respond(exchange, 200, """
                {
                    "device_code": "dc-test-123",
                    "user_code": "ABCD-1234",
                    "verification_uri": "https://fence.example.com/device",
                    "expires_in": 600,
                    "interval": 1
                }
                """);
        });

        var pollCount = new AtomicInteger(0);
        fenceServer.createContext("/oauth2/token", exchange -> {
            int count = pollCount.incrementAndGet();
            if (count <= 2) {
                respond(exchange, 200, "{\"error\": \"authorization_pending\"}");
            } else {
                respond(exchange, 200, """
                    {"access_token": "at-display", "refresh_token": "rt-display", "token_type": "Bearer", "expires_in": 3600}
                    """);
            }
        });

        fenceServer.createContext("/api/me", exchange -> {
            respond(exchange, 200, "{\"id\": \"c-1\", \"displayName\": \"Display User\", \"email\": \"d@example.com\"}");
        });

        int exitCode = execute("login", "--fence-server", "http://localhost:" + fencePort);

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).contains("ABCD-1234");
        assertThat(outContent.toString()).contains("fence.example.com/device");
    }

    @Test
    void login_persistsTokensAndCultivatorToConfig() throws Exception {
        fenceServer.createContext("/oauth2/device_authorization", exchange -> {
            respond(exchange, 200, """
                {
                    "device_code": "dc-test-456",
                    "user_code": "WXYZ-5678",
                    "verification_uri": "https://fence.example.com/device",
                    "expires_in": 600,
                    "interval": 1
                }
                """);
        });

        fenceServer.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, """
                {
                    "access_token": "access-from-login",
                    "refresh_token": "refresh-from-login",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """);
        });

        fenceServer.createContext("/api/me", exchange -> {
            respond(exchange, 200, """
                {
                    "id": "cultivator-login-uuid",
                    "displayName": "Test Cultivator",
                    "email": "test@example.com"
                }
                """);
        });

        int exitCode = execute("login", "--fence-server", "http://localhost:" + fencePort);

        assertThat(exitCode).isZero();

        OrchardConfig config = ConfigLoader.load();
        assertThat(config).isNotNull();
        OrchardConfig.Target target = config.targets().get("local");
        assertThat(target).isNotNull();
        assertThat(target.accessToken()).isEqualTo("access-from-login");
        assertThat(target.refreshToken()).isEqualTo("refresh-from-login");
        assertThat(target.cultivator()).isEqualTo("cultivator-login-uuid");
        assertThat(target.fenceServer()).isEqualTo("http://localhost:" + fencePort);
    }

    @Test
    void login_showsSuccessMessage() throws Exception {
        fenceServer.createContext("/oauth2/device_authorization", exchange -> {
            respond(exchange, 200, """
                {
                    "device_code": "dc-test-789",
                    "user_code": "TEST-9999",
                    "verification_uri": "https://fence.example.com/device",
                    "expires_in": 600,
                    "interval": 1
                }
                """);
        });

        fenceServer.createContext("/oauth2/token", exchange -> {
            respond(exchange, 200, """
                {
                    "access_token": "at-success",
                    "refresh_token": "rt-success",
                    "token_type": "Bearer",
                    "expires_in": 3600
                }
                """);
        });

        fenceServer.createContext("/api/me", exchange -> {
            respond(exchange, 200, """
                {
                    "id": "cult-success",
                    "displayName": "Success User",
                    "email": "success@example.com"
                }
                """);
        });

        int exitCode = execute("login", "--fence-server", "http://localhost:" + fencePort);

        assertThat(exitCode).isZero();
        assertThat(outContent.toString()).containsIgnoringCase("logged in");
        assertThat(outContent.toString()).contains("Success User");
    }

    private int execute(String... args) {
        return Trowel.createCommandLine().execute(args);
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
