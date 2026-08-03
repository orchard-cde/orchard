package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full fence context in standalone mode against a temp signing key so
 * the app's own JWKSet can both sign the test access token and validate it.
 */
@SpringBootTest(properties = {
        "fence.signing-key.path=${java.io.tmpdir}/fence-gateway-token-test/signing-key.jwk",
        "fence.issuer=http://localhost:7779",
        "fence.client.client-id=orchard-ui",
        "fence.client.client-secret=test-secret",
        "fence.gateway-client.client-id=orchard-gateway",
        "fence.gateway-client.client-secret=test-secret"
})
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class GatewayTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JWKSet jwkSet;

    private String signAccessToken(String subject, String email) throws JOSEException {
        RSAKey signingKey = (RSAKey) jwkSet.getKeys().get(0);
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .issuer("http://localhost:7779")
                .audience("orchard-ui")
                .issueTime(new Date(nowSeconds * 1000))
                .expirationTime(new Date((nowSeconds + 3600) * 1000))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void validBearerMintsShortLivedGatewayToken() throws Exception {
        String accessToken = signAccessToken("user-123", "dev@orchard.dev");

        String body = mockMvc.perform(post("/gateway-token")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String gatewayToken = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        SignedJWT jwt = SignedJWT.parse(gatewayToken);
        long nowSeconds = System.currentTimeMillis() / 1000;

        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("user-123");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("dev@orchard.dev");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("gateway-ssh");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("orchard-gateway");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond())
                .isLessThanOrEqualTo(nowSeconds + GatewayTokenService.TTL_SECONDS);
    }

    @Test
    void missingBearerReturns401() throws Exception {
        mockMvc.perform(post("/gateway-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidBearerReturns401() throws Exception {
        mockMvc.perform(post("/gateway-token")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }
}
