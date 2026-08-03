package dev.orchard.fence.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GatewayTokenController {

    private final GatewayTokenService gatewayTokenService;

    public GatewayTokenController(GatewayTokenService gatewayTokenService) {
        this.gatewayTokenService = gatewayTokenService;
    }

    /**
     * Mints a short-lived gateway JWT from the caller's fence-issued access
     * token. The bearer token was already validated by GatewayTokenSecurityConfig
     * against fence's own key; here we only project sub + email forward.
     */
    @PostMapping("/gateway-token")
    public GatewayTokenResponse gatewayToken(@AuthenticationPrincipal Jwt jwt) {
        try {
            return new GatewayTokenResponse(
                    gatewayTokenService.mintGatewayToken(jwt.getSubject(), jwt.getClaimAsString("email")));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
