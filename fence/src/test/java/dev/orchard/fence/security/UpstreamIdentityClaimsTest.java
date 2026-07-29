package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamIdentityClaimsTest {

    @Test
    void extractsStandardClaims() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .subject("google-sub-123")
                .claim(StandardClaimNames.EMAIL, "cultivator@example.com")
                .claim(StandardClaimNames.NAME, "Jane Doe")
                .claim(StandardClaimNames.GIVEN_NAME, "Jane")
                .claim(StandardClaimNames.FAMILY_NAME, "Doe")
                .claim(StandardClaimNames.EMAIL_VERIFIED, true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        DefaultOidcUser oidcUser = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                idToken);

        UpstreamIdentityClaims claims = UpstreamIdentityClaims.from(oidcUser);

        assertThat(claims.subject()).isEqualTo("google-sub-123");
        assertThat(claims.email()).isEqualTo("cultivator@example.com");
        assertThat(claims.fullName()).isEqualTo("Jane Doe");
        assertThat(claims.givenName()).isEqualTo("Jane");
        assertThat(claims.familyName()).isEqualTo("Doe");
        assertThat(claims.emailVerified()).isTrue();
    }

    @Test
    void handlesNullOptionalClaims() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .subject("google-sub-456")
                .claim(StandardClaimNames.EMAIL, "minimal@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        DefaultOidcUser oidcUser = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                idToken);

        UpstreamIdentityClaims claims = UpstreamIdentityClaims.from(oidcUser);

        assertThat(claims.subject()).isEqualTo("google-sub-456");
        assertThat(claims.email()).isEqualTo("minimal@example.com");
        assertThat(claims.fullName()).isNull();
        assertThat(claims.givenName()).isNull();
        assertThat(claims.familyName()).isNull();
        assertThat(claims.emailVerified()).isNull();
    }
}
