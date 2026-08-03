package dev.orchard.fence.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

/**
 * Mints the short-lived JWT the SSH gateway accepts as an SSH password
 * (OwnerTokenAuthenticator). Claims per design spec §2e:
 * {sub, email, scope: gateway-ssh, aud: orchard-gateway, exp, iat, jti}.
 */
@Service
public class GatewayTokenService {

    public static final long TTL_SECONDS = 300;

    private final RSAKey signingKey;

    public GatewayTokenService(JWKSet jwkSet) {
        // JWKSet has no toJWK() method — SigningKeyConfig always produces a set with exactly
        // one RSA key, so take it directly from getKeys().
        JWK jwk = jwkSet.getKeys().get(0);
        if (!(jwk instanceof RSAKey rsaKey) || rsaKey.isPrivate() == Boolean.FALSE) {
            throw new IllegalStateException("Signing key must be a private RSA key");
        }
        this.signingKey = rsaKey;
    }

    public String mintGatewayToken(String subject, String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email claim required");
        }
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("email", email)
                .claim("scope", "gateway-ssh")
                .audience("orchard-gateway")
                .issueTime(new Date(nowSeconds * 1000))
                .expirationTime(new Date((nowSeconds + TTL_SECONDS) * 1000))
                .jwtID(UUID.randomUUID().toString())
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(com.nimbusds.jose.JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign gateway token", e);
        }
    }
}
