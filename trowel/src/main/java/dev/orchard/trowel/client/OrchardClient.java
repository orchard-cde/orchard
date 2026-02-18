package dev.orchard.trowel.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public GroveResponse plantGrove(String repositoryUrl, String branch, String name, String machineSize)
            throws IOException, InterruptedException {
        var request = new PlantGroveRequest(repositoryUrl, branch, name, machineSize);
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

    public void clearGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/groves/" + groveId))
            .header("X-Cultivator-Id", cultivatorId)
            .DELETE()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
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
    public record PlantGroveRequest(String repositoryUrl, String branch, String name, String machineSize) {}

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
}
