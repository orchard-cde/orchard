package dev.orchard.trowel.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.client.OrchardClient.BeeResponse;
import dev.orchard.trowel.client.OrchardClient.GroveResponse;
import dev.orchard.trowel.client.OrchardClient.HealthResponse;
import dev.orchard.trowel.client.OrchardClient.SwarmStatusResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchardClientTest {

    static HttpServer server;
    static OrchardClient client;
    static final String CULTIVATOR_ID = "test-cultivator-123";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        int port = server.getAddress().getPort();
        client = new OrchardClient("http://localhost:" + port, CULTIVATOR_ID);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterEach
    void removeHandlers() {
        // Remove all contexts to avoid handler conflicts between tests
        try { server.removeContext("/api/groves"); } catch (IllegalArgumentException ignored) {}
        try { server.removeContext("/api/health"); } catch (IllegalArgumentException ignored) {}
    }

    // -- plantGrove tests --

    @Test
    void plantGrove_sendsPostWithCorrectBodyAndHeaders() throws Exception {
        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"repositoryUrl\":\"https://github.com/test/repo\"");
            assertThat(body).contains("\"branch\":\"main\"");
            assertThat(body).contains("\"name\":\"my-grove\"");
            assertThat(body).contains("\"machineSize\":\"small\"");

            respond(exchange, 201, GROVE_JSON);
        });

        GroveResponse grove = client.plantGrove("https://github.com/test/repo", "main", "my-grove", "small", "auto");

        assertThat(grove.id()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(grove.name()).isEqualTo("my-grove");
        assertThat(grove.repositoryUrl()).isEqualTo("https://github.com/test/repo");
        assertThat(grove.branch()).isEqualTo("main");
        assertThat(grove.state()).isEqualTo("PLANTING");
    }

    @Test
    void plantGrove_deserializesSeedlingAndFruits() throws Exception {
        server.createContext("/api/groves", exchange -> respond(exchange, 201, GROVE_JSON));

        GroveResponse grove = client.plantGrove("https://github.com/test/repo", "main", "my-grove", "small", "auto");

        assertThat(grove.seedling()).isNotNull();
        assertThat(grove.seedling().ipAddress()).isEqualTo("192.168.1.100");
        assertThat(grove.seedling().sshPort()).isEqualTo(22);
        assertThat(grove.seedling().cpuCores()).isEqualTo(2);
        assertThat(grove.seedling().memoryMb()).isEqualTo(4096);

        assertThat(grove.fruits()).hasSize(1);
        assertThat(grove.fruits().getFirst().containerName()).isEqualTo("devcontainer");
        assertThat(grove.fruits().getFirst().state()).isEqualTo("RIPENING");
    }

    @Test
    void plantGrove_throwsOnServerError() {
        server.createContext("/api/groves", exchange -> respond(exchange, 500, "{\"error\":\"internal\"}"));

        assertThatThrownBy(() -> client.plantGrove("https://github.com/test/repo", "main", "n", "small", "auto"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("500");
    }

    @Test
    void plantGrove_throwsOnValidationError() {
        server.createContext("/api/groves", exchange -> respond(exchange, 400, "{\"error\":\"bad request\"}"));

        assertThatThrownBy(() -> client.plantGrove("", "main", null, "small", "auto"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("400");
    }

    // -- listGroves tests --

    @Test
    void listGroves_sendsGetWithCultivatorHeader() throws Exception {
        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, "[" + GROVE_JSON + "]");
        });

        List<GroveResponse> groves = client.listGroves();

        assertThat(groves).hasSize(1);
        assertThat(groves.getFirst().name()).isEqualTo("my-grove");
    }

    @Test
    void listGroves_handlesEmptyList() throws Exception {
        server.createContext("/api/groves", exchange -> respond(exchange, 200, "[]"));

        List<GroveResponse> groves = client.listGroves();

        assertThat(groves).isEmpty();
    }

    // -- getGrove tests --

    @Test
    void getGrove_sendsGetToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId);
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, GROVE_JSON);
        });

        GroveResponse grove = client.getGrove(groveId);

        assertThat(grove.id()).isEqualTo(groveId);
        assertThat(grove.state()).isEqualTo("PLANTING");
    }

    @Test
    void getGrove_throwsOnNotFound() {
        server.createContext("/api/groves", exchange -> respond(exchange, 404, "{\"error\":\"not found\"}"));

        assertThatThrownBy(() -> client.getGrove(UUID.randomUUID()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("404");
    }

    // -- clearGrove tests --

    @Test
    void clearGrove_sendsDeleteToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId);
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 204, "");
        });

        client.clearGrove(groveId);
        // No exception = success for 204
    }

    @Test
    void clearGrove_throwsOnNotFound() {
        server.createContext("/api/groves", exchange -> respond(exchange, 404, "{\"error\":\"not found\"}"));

        assertThatThrownBy(() -> client.clearGrove(UUID.randomUUID()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("404");
    }

    // -- checkHealth tests --

    @Test
    void checkHealth_sendsGetAndDeserializesResponse() throws Exception {
        server.createContext("/api/health", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            // Health endpoint should NOT require cultivator header
            respond(exchange, 200, """
                {"status":"healthy","name":"Orchard","version":"0.1.0-SNAPSHOT"}
                """);
        });

        HealthResponse health = client.checkHealth();

        assertThat(health.status()).isEqualTo("healthy");
        assertThat(health.name()).isEqualTo("Orchard");
        assertThat(health.version()).isEqualTo("0.1.0-SNAPSHOT");
    }

    // -- installBee tests --

    @Test
    void installBee_sendsPostWithCorrectBodyAndHeaders() throws Exception {
        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"beeType\":\"OPENCODE\"");
            assertThat(body).contains("\"version\":\"latest\"");

            respond(exchange, 201, BEE_JSON);
        });

        BeeResponse bee = client.installBee(UUID.fromString("11111111-1111-1111-1111-111111111111"), "OPENCODE", "latest");

        assertThat(bee.id()).isEqualTo(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        assertThat(bee.type()).isEqualTo("OPENCODE");
        assertThat(bee.state()).isEqualTo("HIBERNATING");
    }

    @Test
    void installBee_throwsOnValidationError() {
        server.createContext("/api/groves", exchange -> respond(exchange, 400, "{\"error\":\"bad request\"}"));

        assertThatThrownBy(() -> client.installBee(UUID.randomUUID(), "OPENCODE", null))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("400");
    }

    // -- listBees tests --

    @Test
    void listBees_sendsGetToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId + "/bees");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, "[" + BEE_JSON + "]");
        });

        List<BeeResponse> bees = client.listBees(groveId);

        assertThat(bees).hasSize(1);
        assertThat(bees.getFirst().type()).isEqualTo("OPENCODE");
    }

    @Test
    void listBees_handlesEmptyList() throws Exception {
        server.createContext("/api/groves", exchange -> respond(exchange, 200, "[]"));

        List<BeeResponse> bees = client.listBees(UUID.randomUUID());

        assertThat(bees).isEmpty();
    }

    // -- showBee tests --

    @Test
    void showBee_sendsGetToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID beeId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId + "/bees/" + beeId);
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, BEE_JSON);
        });

        BeeResponse bee = client.showBee(groveId, beeId);

        assertThat(bee.id()).isEqualTo(beeId);
        assertThat(bee.state()).isEqualTo("HIBERNATING");
    }

    @Test
    void showBee_throwsOnNotFound() {
        server.createContext("/api/groves", exchange -> respond(exchange, 404, "{\"error\":\"not found\"}"));

        assertThatThrownBy(() -> client.showBee(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("404");
    }

    // -- wakeBee / smokeBee tests --

    @Test
    void wakeBee_sendsPostToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID beeId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId + "/bees/" + beeId + "/actions/wake");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, BEE_JSON.replace("\"HIBERNATING\"", "\"BUZZING\""));
        });

        BeeResponse bee = client.wakeBee(groveId, beeId);

        assertThat(bee.state()).isEqualTo("BUZZING");
    }

    @Test
    void smokeBee_sendsPostToCorrectPath() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID beeId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId + "/bees/" + beeId + "/actions/smoke");
            assertThat(exchange.getRequestHeaders().getFirst("X-Cultivator-Id")).isEqualTo(CULTIVATOR_ID);

            respond(exchange, 200, BEE_JSON.replace("\"HIBERNATING\"", "\"SMOKED\""));
        });

        BeeResponse bee = client.smokeBee(groveId, beeId);

        assertThat(bee.state()).isEqualTo("SMOKED");
    }

    // -- getSwarmStatus tests --

    @Test
    void getSwarmStatus_sendsGetAndDeserializesResponse() throws Exception {
        UUID groveId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        server.createContext("/api/groves", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/api/groves/" + groveId + "/bees/status");

            respond(exchange, 200, """
                {"groveId":"11111111-1111-1111-1111-111111111111","totalBees":2,"byState":{"HIBERNATING":1,"BUZZING":1}}
                """);
        });

        SwarmStatusResponse status = client.getSwarmStatus(groveId);

        assertThat(status.totalBees()).isEqualTo(2);
        assertThat(status.byState()).containsEntry("HIBERNATING", 1);
        assertThat(status.byState()).containsEntry("BUZZING", 1);
    }

    // -- baseUrl normalization --

    @Test
    void client_handlesTrailingSlashInBaseUrl() throws Exception {
        int port = server.getAddress().getPort();
        OrchardClient slashClient = new OrchardClient("http://localhost:" + port + "/", CULTIVATOR_ID);

        server.createContext("/api/health", exchange ->
            respond(exchange, 200, """
                {"status":"healthy","name":"Orchard","version":"0.1.0"}
                """)
        );

        HealthResponse health = slashClient.checkHealth();
        assertThat(health.status()).isEqualTo("healthy");
    }

    @Test
    void primaryFruit_returnsFirstFruit() throws Exception {
        server.createContext("/api/groves", exchange -> respond(exchange, 200, GROVE_JSON));

        GroveResponse grove = client.getGrove(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        assertThat(grove.primaryFruit()).isNotNull();
        assertThat(grove.primaryFruit().containerName()).isEqualTo("devcontainer");
    }

    @Test
    void primaryFruit_returnsNullWhenNoFruits() throws Exception {
        server.createContext("/api/groves", exchange -> respond(exchange, 200, """
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "empty-grove",
              "repositoryUrl": "https://github.com/test/repo",
              "branch": "main",
              "state": "PREPARING",
              "fruits": []
            }
            """));

        GroveResponse grove = client.getGrove(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        assertThat(grove.primaryFruit()).isNull();
    }

    // -- Helpers --

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

    static final String GROVE_JSON = """
        {
          "id": "11111111-1111-1111-1111-111111111111",
          "name": "my-grove",
          "repositoryUrl": "https://github.com/test/repo",
          "branch": "main",
          "commitSha": "abc123",
          "state": "PLANTING",
          "sshConnectionString": "ssh user@192.168.1.100",
          "seedling": {
            "id": "22222222-2222-2222-2222-222222222222",
            "state": "SPROUTING",
            "ipAddress": "192.168.1.100",
            "sshPort": 22,
            "cpuCores": 2,
            "memoryMb": 4096,
            "diskGb": 50
          },
          "fruits": [
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "state": "RIPENING",
              "containerId": "abc123def456",
              "containerName": "devcontainer",
              "serviceName": "app"
            }
          ],
          "plantedAt": "2026-02-26T10:00:00Z",
          "lastAccessedAt": "2026-02-26T10:05:00Z"
        }
        """;

    static final String BEE_JSON = """
        {
          "id": "44444444-4444-4444-4444-444444444444",
          "groveId": "11111111-1111-1111-1111-111111111111",
          "type": "OPENCODE",
          "state": "HIBERNATING",
          "processId": null,
          "hatchedAt": "2026-07-18T10:30:00Z",
          "startedAt": null,
          "stoppedAt": null
        }
        """;
}
