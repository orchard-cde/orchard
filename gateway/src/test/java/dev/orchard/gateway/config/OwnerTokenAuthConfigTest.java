package dev.orchard.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerTokenAuthConfigTest {

    private HttpServer jwksStub;

    @AfterEach
    void tearDown() {
        if (jwksStub != null) {
            jwksStub.stop(0);
        }
    }

    @Test
    void ownerTokenDecoder_buildsFromFenceIssuerUri() {
        GatewayProperties properties = new GatewayProperties();
        JwtDecoder decoder = new OwnerTokenAuthConfig().ownerTokenDecoder(properties);
        assertThat(decoder).isNotNull();
    }

    /**
     * Regression guard for the load-bearing "no issuer" decision documented on
     * {@link OwnerTokenAuthConfig}: fence's minted owner tokens carry no {@code iss}
     * claim, so the bean MUST install {@code JwtValidators.createDefault()} rather than
     * {@code createDefaultWithIssuer(...)}. The smoke test above (asserting non-null)
     * would keep passing even if that regressed, silently rejecting every real owner
     * token in production. This test decodes an issuer-less token through the ACTUAL
     * {@code ownerTokenDecoder} bean, against a real (stubbed) JWKS endpoint, and fails
     * the moment an issuer validator is reintroduced.
     */
    @Test
    void ownerTokenDecoder_acceptsIssuerlessTokenFetchedFromRealJwks() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        jwksStub = startJwksStub(rsaKey.toPublicJWK());

        GatewayProperties properties = new GatewayProperties();
        properties.getFence().setIssuerUri("http://127.0.0.1:" + jwksStub.getAddress().getPort());
        JwtDecoder decoder = new OwnerTokenAuthConfig().ownerTokenDecoder(properties);

        String issuerlessToken = sign(rsaKey, new JWTClaimsSet.Builder()
                .subject("alice@example.com")
                .claim("email", "alice@example.com")
                .audience("orchard-gateway")
                .claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        Jwt jwt = decoder.decode(issuerlessToken);

        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice@example.com");
        assertThat(jwt.getIssuer()).isNull();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    private static HttpServer startJwksStub(RSAKey publicJwk) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = new JWKSet(publicJwk).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/oauth2/jwks", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        return server;
    }
}
