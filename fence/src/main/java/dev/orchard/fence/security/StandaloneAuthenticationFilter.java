package dev.orchard.fence.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "fence.standalone.enabled", havingValue = "true")
public class StandaloneAuthenticationFilter extends OncePerRequestFilter {

    static final String STANDALONE_SUBJECT = "dev@localhost";
    static final String STANDALONE_EMAIL = "dev@localhost";
    static final String STANDALONE_NAME = "Dev User";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(standaloneAuthentication());
        filterChain.doFilter(request, response);
    }

    private static OAuth2AuthenticationToken standaloneAuthentication() {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("standalone")
                .claim(IdTokenClaimNames.SUB, STANDALONE_SUBJECT)
                .claim("email", STANDALONE_EMAIL)
                .claim("name", STANDALONE_NAME)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        DefaultOidcUser oidcUser = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken);
        return new OAuth2AuthenticationToken(oidcUser, AuthorityUtils.NO_AUTHORITIES, "standalone");
    }
}
