package dev.orchard.trowel.auth;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for Fence OAuth 2.0 device authorization grant (RFC 8628).
 */
public class FenceClient {

    private final String fenceServerUrl;
    private final String clientId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FenceClient(String fenceServerUrl, String clientId) {
        this.fenceServerUrl = fenceServerUrl.endsWith("/")
            ? fenceServerUrl.substring(0, fenceServerUrl.length() - 1)
            : fenceServerUrl;
        this.clientId = clientId;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    }

    public DeviceAuthorizationResponse requestDeviceAuthorization() throws FenceAuthException {
        try {
            String body = encodeForm(Map.of(
                "client_id", clientId,
                "scope", "openid"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fenceServerUrl + "/device/authorize"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new FenceAuthException("Fence returned " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readValue(response.body(), DeviceAuthorizationResponse.class);
        } catch (FenceAuthException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new FenceAuthException("Failed to request device authorization: " + e.getMessage());
        }
    }

    public TokenResponse pollDeviceToken(String deviceCode) throws FenceAuthException {
        String body = encodeForm(Map.of(
            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
            "device_code", deviceCode,
            "client_id", clientId
        ));
        return exchangeToken(body, InvalidGrantException::new);
    }

    public TokenResponse refreshToken(String refreshToken) throws FenceAuthException {
        String body = encodeForm(Map.of(
            "grant_type", "refresh_token",
            "refresh_token", refreshToken,
            "client_id", clientId
        ));
        return exchangeToken(body, RefreshTokenInvalidException::new);
    }

    private TokenResponse exchangeToken(String formBody, java.util.function.Supplier<FenceAuthException> invalidGrantFactory) throws FenceAuthException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fenceServerUrl + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            ErrorResponse errorResponse;
            try {
                errorResponse = objectMapper.readValue(response.body(), ErrorResponse.class);
            } catch (Exception e) {
                errorResponse = new ErrorResponse(null);
            }

            if (errorResponse.error() != null) {
                throw switch (errorResponse.error()) {
                    case "authorization_pending" -> new AuthorizationPendingException();
                    case "slow_down" -> new SlowDownException();
                    case "expired_token" -> new DeviceCodeExpiredException();
                    case "access_denied" -> new AuthorizationDeniedException();
                    case "invalid_grant" -> invalidGrantFactory.get();
                    default -> new FenceAuthException("Token exchange failed: " + errorResponse.error());
                };
            }

            if (response.statusCode() >= 400) {
                throw new FenceAuthException("Token exchange failed with status " + response.statusCode() + ": " + response.body());
            }

            return objectMapper.readValue(response.body(), TokenResponse.class);
        } catch (FenceAuthException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new FenceAuthException("Token exchange failed: " + e.getMessage());
        }
    }

    private static String encodeForm(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public record DeviceAuthorizationResponse(
        String deviceCode,
        String userCode,
        String verificationUri,
        int expiresIn,
        int interval
    ) {}

    public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        int expiresIn
    ) {}

    private record ErrorResponse(String error) {}
}
