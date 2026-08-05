package dev.orchard.gateway.auth;

import dev.orchard.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints and caches the OAuth2 client_credentials service token the gateway
 * presents to trellis's /api/gateway/** endpoints. The client (orchard-gateway)
 * is registered in fence with CLIENT_SECRET_BASIC (Phase 2).
 * Wired as a bean in {@code HttpClientConfig} (not component-scanned to avoid a duplicate/circular bean).
 */
public class FenceTokenClient {

    private static final Logger log = LoggerFactory.getLogger(FenceTokenClient.class);

    private final RestClient restClient;
    private final GatewayProperties properties;

    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public FenceTokenClient(RestClient restClient, GatewayProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public synchronized String accessToken() {
        if (accessToken != null && expiresAt.isAfter(Instant.now().plusSeconds(30))) {
            return accessToken;
        }
        String tokenEndpoint = properties.getFence().getIssuerUri() + "/oauth2/token";
        String basic = Base64.getEncoder().encodeToString(
                (properties.getOauth2().getClientId() + ":" + properties.getOauth2().getClientSecret())
                        .getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "gateway");

        JsonNode body = restClient.post()
                .uri(tokenEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        accessToken = body.get("access_token").asString();
        long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600;
        expiresAt = Instant.now().plusSeconds(expiresIn);
        log.debug("Fetched gateway service token (expires in {}s)", expiresIn);
        return accessToken;
    }
}
