package dev.orchard.gateway.auth;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.orchard.gateway.auth.KeyAuthenticator.ROUTE_KEY;

/**
 * MINA PasswordAuthenticator for the owner-token flow: the user runs
 * {@code ssh <grove-id>@<gateway-host>} and pastes a short-lived fence JWT
 * (POST /gateway-token) as the password. The token must be signed by fence,
 * carry the gateway audience and scope, and the bearer must own the grove —
 * trellis authorize-owner enforces the last check. On success the resolved
 * route is stashed under the same {@code ROUTE_KEY} KeyAuthenticator uses.
 */
@Component
public class OwnerTokenAuthenticator implements PasswordAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(OwnerTokenAuthenticator.class);
    private static final String GATEWAY_AUDIENCE = "orchard-gateway";
    private static final String GATEWAY_SCOPE = "gateway-ssh";

    private final JwtDecoder jwtDecoder;
    private final TrellisApiClient trellisApiClient;

    public OwnerTokenAuthenticator(JwtDecoder jwtDecoder, TrellisApiClient trellisApiClient) {
        this.jwtDecoder = jwtDecoder;
        this.trellisApiClient = trellisApiClient;
    }

    @Override
    public boolean authenticate(String username, String password, ServerSession session) {
        UUID groveId;
        try {
            groveId = UUID.fromString(username);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        if (password == null || password.isBlank()) {
            return false;
        }

        final Jwt jwt;
        try {
            jwt = jwtDecoder.decode(password);
        } catch (JwtException e) {
            log.debug("Owner token rejected: {}", e.getMessage());
            return false;
        }

        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(GATEWAY_AUDIENCE)) {
            log.debug("Owner token rejected: wrong audience");
            return false;
        }
        String scope = jwt.getClaimAsString("scope");
        if (!GATEWAY_SCOPE.equals(scope)) {
            log.debug("Owner token rejected: missing {} scope", GATEWAY_SCOPE);
            return false;
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            log.debug("Owner token rejected: no email claim");
            return false;
        }

        Optional<GatewayRoute> route;
        try {
            route = trellisApiClient.authorizeOwner(groveId, email);
        } catch (Exception e) {
            log.debug("Owner token rejected: trellis authorize-owner call failed: {}", e.getMessage());
            return false;
        }
        if (route.isEmpty()) {
            log.debug("Owner token rejected: {} is not an authorized owner of grove {}", email, groveId);
            return false;
        }
        session.setAttribute(ROUTE_KEY, route.get());
        return true;
    }
}
