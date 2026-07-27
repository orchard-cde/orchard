package dev.orchard.fence.security;

import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public record UpstreamIdentityClaims(
        String subject,
        String email,
        String fullName,
        String givenName,
        String familyName,
        Boolean emailVerified
) {
    public static UpstreamIdentityClaims from(OidcUser oidcUser) {
        return new UpstreamIdentityClaims(
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                oidcUser.getFullName(),
                oidcUser.getGivenName(),
                oidcUser.getFamilyName(),
                oidcUser.getEmailVerified()
        );
    }
}
