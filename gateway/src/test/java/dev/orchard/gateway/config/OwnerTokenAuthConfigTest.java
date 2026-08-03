package dev.orchard.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerTokenAuthConfigTest {

    @Test
    void ownerTokenDecoder_buildsFromFenceIssuerUri() {
        GatewayProperties properties = new GatewayProperties();
        JwtDecoder decoder = new OwnerTokenAuthConfig().ownerTokenDecoder(properties);
        assertThat(decoder).isNotNull();
    }
}
