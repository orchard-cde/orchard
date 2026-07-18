package dev.orchard.trowel.client;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for communicating with the Orchard server.
 */
public class OrchardClient {

    private final String baseUrl;
    private final String cultivatorId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OrchardClient(String baseUrl, String cultivatorId) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cultivatorId = cultivatorId;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    public GroveResponse plantGrove(String repositoryUrl, String branch, String name, String machineSize, String spec)
            throws IOException, InterruptedException {
        var request = new PlantGroveRequest(repositoryUrl, branch, name, machineSize, spec);
        String body = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves"))
            .header("Content-Type", "application/json")
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public GroveResponse getGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId))
            .header("X-Cultivator-Id", cultivatorId)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public List<GroveResponse> listGroves() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves"))
            .header("X-Cultivator-Id", cultivatorId)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<GroveResponse>>() {});
    }

    public GroveResponse stopGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/actions/stop"))
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public GroveResponse startGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/actions/start"))
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public void clearGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId))
            .header("X-Cultivator-Id", cultivatorId)
            .DELETE()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
    }

    public BeeResponse installBee(UUID groveId, String beeType, String version)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(
            Map.of("beeType", beeType, "version", version != null ? version : "", "configOverrides", Map.of())
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees"))
            .header("Content-Type", "application/json")
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public List<BeeResponse> listBees(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees"))
            .header("X-Cultivator-Id", cultivatorId)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<BeeResponse>>() {});
    }

    public BeeResponse showBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId))
            .header("X-Cultivator-Id", cultivatorId)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public BeeResponse wakeBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId + "/actions/wake"))
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public BeeResponse smokeBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId + "/actions/smoke"))
            .header("X-Cultivator-Id", cultivatorId)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public SwarmStatusResponse getSwarmStatus(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/status"))
            .header("X-Cultivator-Id", cultivatorId)
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), SwarmStatusResponse.class);
    }

    public HealthResponse checkHealth() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/health"))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), HealthResponse.class);
    }

    private void checkResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 400) {
            throw new IOException("Server returned " + response.statusCode() + ": " + response.body());
        }
    }

    // Request/Response records
    public record PlantGroveRequest(String repositoryUrl, String branch, String name, String machineSize, String spec) {}

    public record GroveResponse(
        UUID id,
        String name,
        String repositoryUrl,
        String branch,
        String commitSha,
        String state,
        String sshConnectionString,
        SeedlingInfo seedling,
        List<FruitInfo> fruits,
        String plantedAt,
        String lastAccessedAt
    ) {
        /** Returns the primary fruit (first in the list) for backward compatibility. */
        public FruitInfo primaryFruit() {
            return fruits != null && !fruits.isEmpty() ? fruits.getFirst() : null;
        }
    }

    public record SeedlingInfo(UUID id, String state, String ipAddress, int sshPort, int cpuCores, int memoryMb, int diskGb) {}
    public record FruitInfo(UUID id, String state, String containerId, String containerName, String serviceName) {}
    public record HealthResponse(String status, String name, String version) {}

    public record BeeResponse(
        UUID id,
        UUID groveId,
        String type,
        String state,
        String processId,
        String hatchedAt,
        String startedAt,
        String stoppedAt
    ) {}

    public record SwarmStatusResponse(
        UUID groveId,
        int totalBees,
        Map<String, Integer> byState
    ) {}
}
