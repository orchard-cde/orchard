package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTokenServiceTest {

    private GatewayTokenService service;

    @BeforeEach
    void setUp() throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048).generate();
        service = new GatewayTokenService(new JWKSet(rsaKey));
    }

    @Test
    void mintsTokenWithGatewayClaims() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.mintGatewayToken("user-123", "dev@orchard.dev"));

        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-123");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("dev@orchard.dev");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("gateway-ssh");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("orchard-gateway");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
    }

    @Test
    void mintsTokenExpiringWithinFiveMinutes() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.mintGatewayToken("user-123", "dev@orchard.dev"));

        long nowSeconds = System.currentTimeMillis() / 1000;
        long exp = jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond();
        assertThat(exp).isGreaterThan(nowSeconds);
        assertThat(exp).isLessThanOrEqualTo(nowSeconds + GatewayTokenService.TTL_SECONDS);
    }

    @Test
    void rejectsMissingEmail() {
        assertThatThrownBy(() -> service.mintGatewayToken("user-123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsBlankEmail() {
        assertThatThrownBy(() -> service.mintGatewayToken("user-123", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
