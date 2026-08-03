package dev.orchard.trellis.security;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.core.model.Cultivator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that extracts cultivator identity from a validated JWT token.
 * <p>
 * After Spring Security validates the JWT, this filter reads standard OIDC claims
 * (sub, email, name, picture) and uses {@link CultivatorService}
 * to find or create the corresponding cultivator. The cultivator's ID is then stored
 * as a request attribute ("cultivatorId") for use by downstream controllers.
 * <p>
 * Only active when OAuth2 security is enabled (orchard.security.oauth2.enabled=true).
 */
@Component
@ConditionalOnProperty(name = "orchard.security.oauth2.enabled", havingValue = "true")
public class CultivatorAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CultivatorAuthFilter.class);

    private final CultivatorService cultivatorService;

    public CultivatorAuthFilter(CultivatorService cultivatorService) {
        this.cultivatorService = cultivatorService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();

            // Service tokens (e.g. the SSH gateway's client_credentials JWT) carry no
            // email claim and must not be resolved into a cultivator. Interactive user
            // tokens always carry email via the openid scope.
            String email = jwt.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                log.debug("JWT has no email claim; skipping cultivator resolution (service token?)");
                filterChain.doFilter(request, response);
                return;
            }

            // fence is the sole JWT issuer regardless of upstream IdP, so the issuer URL no
            // longer identifies the upstream provider. Hardcoded until multi-IdP support
            // (orchard#196) adds a provider claim to the token itself.
            String provider = "google";
            String providerId = jwt.getSubject();
            String username = resolveUsername(jwt);
            String avatarUrl = jwt.getClaimAsString("picture");
            String displayName = jwt.getClaimAsString("name");

            if (providerId != null) {
                try {
                    Cultivator cultivator = cultivatorService.findOrCreateCultivator(
                        provider, providerId, username, email, avatarUrl, displayName
                    );
                    request.setAttribute("cultivatorId", cultivator.id());
                    log.debug("Resolved cultivator {} from JWT sub={}", cultivator.id(), providerId);
                } catch (Exception e) {
                    log.error("Failed to resolve cultivator from JWT: {}", e.getMessage(), e);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolves a username from JWT claims. Email is used directly rather than a
     * derived local-part (e.g. "jane" from "jane@example.com"), since only email
     * is guaranteed unique across upstream IdPs/domains — a local-part collision
     * between two different domains would violate the username uniqueness
     * constraint on the cultivators table.
     */
    private String resolveUsername(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }

        return jwt.getSubject();
    }
}
