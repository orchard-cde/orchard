package dev.orchard.gateway.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerTokenAuthenticatorTest {

    private static RSAKey rsaKey() throws Exception {
        return new RSAKeyGenerator(2048).generate();
    }

    // No setJwtValidator call: NimbusJwtDecoder defaults to JwtValidators.createDefault(),
    // which does NOT require an issuer — matching production (OwnerTokenAuthConfig also
    // uses createDefault(), since the gateway token fence mints carries no iss claim).
    private static JwtDecoder decoderFor(RSAKey key) throws Exception {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.toRSAPublicKey()).build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    // No .issuer(...) claim, mirroring the real token minted by fence's
    // GatewayTokenService (sub/email/scope/aud/iat/exp/jti only, no iss).
    private static JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .subject("alice@example.com")
                .claim("email", "alice@example.com")
                .audience("orchard-gateway")
                .claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
    }

    private static OwnerTokenAuthenticator authenticator(RSAKey key, TrellisApiClient trellis) throws Exception {
        return new OwnerTokenAuthenticator(decoderFor(key), trellis);
    }

    @Test
    void authenticate_acceptsOwnerTokenAndStoresRoute() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, validClaims());
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        UUID groveId = UUID.randomUUID();
        GatewayRoute route = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        when(trellis.authorizeOwner(groveId, "alice@example.com")).thenReturn(Optional.of(route));
        ServerSession session = mock(ServerSession.class);

        assertThat(authenticator(key, trellis).authenticate(groveId.toString(), token, session)).isTrue();
        verify(session).setAttribute(eq(KeyAuthenticator.ROUTE_KEY), eq(route));
    }

    @Test
    void authenticate_rejectsExpiredToken() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("orchard-gateway").claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenWithoutGatewayScope() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("orchard-gateway").claim("scope", "other")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenForWrongAudience() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, new JWTClaimsSet.Builder()
                .subject("alice@example.com").claim("email", "alice@example.com")
                .audience("some-other-service").claim("scope", "gateway-ssh")
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build());

        assertThat(authenticator(key, mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenSignedByAnotherKey() throws Exception {
        String token = sign(rsaKey(), validClaims());

        assertThat(authenticator(rsaKey(), mock(TrellisApiClient.class))
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsTokenWhenOwnerIsNotAuthorized() throws Exception {
        RSAKey key = rsaKey();
        String token = sign(key, validClaims());
        TrellisApiClient trellis = mock(TrellisApiClient.class);
        when(trellis.authorizeOwner(any(), eq("alice@example.com"))).thenReturn(Optional.empty());

        assertThat(authenticator(key, trellis)
                .authenticate(UUID.randomUUID().toString(), token, mock(ServerSession.class))).isFalse();
    }

    @Test
    void authenticate_rejectsMalformedUsernameAndBlankPassword() throws Exception {
        OwnerTokenAuthenticator authenticator = authenticator(rsaKey(), mock(TrellisApiClient.class));
        assertThat(authenticator.authenticate("not-a-uuid", "whatever", mock(ServerSession.class))).isFalse();
        assertThat(authenticator.authenticate(UUID.randomUUID().toString(), "", mock(ServerSession.class))).isFalse();
        assertThat(authenticator.authenticate(UUID.randomUUID().toString(), null, mock(ServerSession.class))).isFalse();
    }
}
