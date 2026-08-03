package dev.orchard.gateway.api;

import dev.orchard.gateway.auth.FenceTokenClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP client for trellis /api/gateway/**. Authenticates with the gateway's
 * client_credentials service token (fence), injected by the bearer interceptor.
 * 404 (unknown grove) and 409 (exists but not routable) both mean "no route".
 * Wired as a bean in {@code HttpClientConfig} (not component-scanned to avoid a duplicate/circular bean).
 */
public class TrellisApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TrellisApiClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** Builds a RestClient with a bearer-token interceptor that refreshes the token transparently. */
    public static RestClient buildRestClient(String baseUrl, FenceTokenClient tokenClient) {
        return buildRestClient(RestClient.builder(), baseUrl, tokenClient);
    }

    /** Same as {@link #buildRestClient(String, FenceTokenClient)}, but against a caller-supplied builder (tests bind a mock server to it first). */
    public static RestClient buildRestClient(RestClient.Builder builder, String baseUrl, FenceTokenClient tokenClient) {
        return builder
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenClient.accessToken());
                    return execution.execute(request, body);
                })
                .build();
    }

    public Optional<GatewayRoute> resolveGrove(UUID groveId) {
        return restClient.get()
                .uri("/api/gateway/groves/{id}", groveId)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200) {
                        return Optional.of(objectMapper.readValue(response.getBody(), GatewayRoute.class));
                    }
                    if (status == 404 || status == 409) {
                        return Optional.<GatewayRoute>empty();
                    }
                    throw new IllegalStateException("Unexpected status " + status + " from trellis");
                });
    }

    public List<GatewayKey> listKeys(UUID cultivatorId) {
        return restClient.get()
                .uri("/api/gateway/cultivators/{id}/keys", cultivatorId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GatewayKey>>() {});
    }
}
