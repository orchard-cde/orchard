package dev.orchard.trowel.client;

import dev.orchard.trowel.auth.AuthProvider;
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
    private final AuthProvider authProvider;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OrchardClient(String baseUrl, AuthProvider authProvider) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authProvider = authProvider;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    }

    private HttpRequest.Builder authenticated(HttpRequest.Builder builder) throws IOException, InterruptedException {
        String header = authProvider.authorizationHeader();
        if (header != null) {
            builder.header("Authorization", header);
        }
        return builder;
    }

    public GroveResponse plantGrove(String repositoryUrl, String branch, String name, String machineSize, String spec)
            throws IOException, InterruptedException {
        var request = new PlantGroveRequest(repositoryUrl, branch, name, machineSize, spec);
        String body = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        ).build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public GroveResponse getGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public List<GroveResponse> listGroves() throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<GroveResponse>>() {});
    }

    public GroveResponse stopGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/actions/stop"))
                .POST(HttpRequest.BodyPublishers.noBody())
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public GroveResponse startGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/actions/start"))
                .POST(HttpRequest.BodyPublishers.noBody())
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), GroveResponse.class);
    }

    public void clearGrove(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId))
                .DELETE()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
    }

    public BeeResponse installBee(UUID groveId, String beeType, String version)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(
            Map.of("beeType", beeType, "version", version != null ? version : "", "configOverrides", Map.of())
        );

        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public List<BeeResponse> listBees(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<BeeResponse>>() {});
    }

    public BeeResponse showBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public BeeResponse wakeBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId + "/actions/wake"))
                .POST(HttpRequest.BodyPublishers.noBody())
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public BeeResponse smokeBee(UUID groveId, UUID beeId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/" + beeId + "/actions/smoke"))
                .POST(HttpRequest.BodyPublishers.noBody())
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), BeeResponse.class);
    }

    public SwarmStatusResponse getSwarmStatus(UUID groveId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/groves/" + groveId + "/bees/status"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), SwarmStatusResponse.class);
    }

    public CultivatorResponse getCurrentCultivator() throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/me"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), CultivatorResponse.class);
    }

    public SshPublicKeyResponse registerSshPublicKey(String name, String publicKey)
            throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(new CreateSshPublicKeyRequest(name, publicKey));

        HttpRequest httpRequest = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        ).build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), SshPublicKeyResponse.class);
    }

    public List<SshPublicKeyResponse> listSshPublicKeys() throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys"))
                .GET()
        ).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        return objectMapper.readValue(response.body(), new TypeReference<List<SshPublicKeyResponse>>() {});
    }

    public void deleteSshPublicKey(UUID keyId) throws IOException, InterruptedException {
        HttpRequest request = authenticated(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ssh-keys/" + keyId))
                .DELETE()
        ).build();

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
    public record CultivatorResponse(String id, String email, String displayName) {}

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

    public record CreateSshPublicKeyRequest(String name, String publicKey) {}

    public record SshPublicKeyResponse(
        UUID id,
        String name,
        String publicKey,
        String fingerprint,
        String createdAt
    ) {}
}
