package dev.orchard.fence.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class TokenClaimsConfig {

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenClaimsCustomizer() {
        return context -> {
            if (context.getPrincipal().getPrincipal() instanceof OidcUser oidcUser) {
                UpstreamIdentityClaims claims = UpstreamIdentityClaims.from(oidcUser);
                context.getClaims().claim("sub", claims.subject());
                context.getClaims().claim("email", claims.email());
                if (claims.fullName() != null) {
                    context.getClaims().claim("name", claims.fullName());
                }
                if (claims.givenName() != null) {
                    context.getClaims().claim("given_name", claims.givenName());
                }
                if (claims.familyName() != null) {
                    context.getClaims().claim("family_name", claims.familyName());
                }
            }
        };
    }
}
