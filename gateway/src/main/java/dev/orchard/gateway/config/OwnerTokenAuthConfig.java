package dev.orchard.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decodes owner tokens minted by fence's POST /gateway-token (Phase 2) that
 * the CLI pastes as an SSH password. JWKS is fetched from fence's issuer, the
 * same way trellis validates fence JWTs via issuer-uri. The RemoteJWKSet
 * resolves lazily, so building this bean never touches the network.
 *
 * <p>The minted token carries no {@code iss} claim (see
 * {@code fence.gateway.GatewayTokenService}), so the default validator
 * ({@code JwtValidators.createDefault()}, which NimbusJwtDecoder installs
 * automatically) is used rather than {@code createDefaultWithIssuer} — an
 * issuer validator would reject every real owner token. Audience and scope
 * are enforced separately by {@code OwnerTokenAuthenticator}.
 */
@Configuration
public class OwnerTokenAuthConfig {

    @Bean
    public JwtDecoder ownerTokenDecoder(GatewayProperties properties) {
        String issuerUri = properties.getFence().getIssuerUri();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuerUri + "/oauth2/jwks").build();
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }
}
