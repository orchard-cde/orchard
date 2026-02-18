package dev.orchard.server.security;

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
 * (sub, email, preferred_username, name, picture) and uses {@link CultivatorService}
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

            String provider = extractIssuerShortName(jwt.getIssuer() != null ? jwt.getIssuer().toString() : "oidc");
            String providerId = jwt.getSubject();
            String email = jwt.getClaimAsString("email");
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
     * Extracts a short provider name from the OIDC issuer URL.
     * For example, "https://accounts.google.com" becomes "google",
     * "https://github.com" becomes "github".
     */
    private String extractIssuerShortName(String issuerUri) {
        try {
            String host = java.net.URI.create(issuerUri).getHost();
            if (host == null) return "oidc";

            // Extract second-level domain (e.g., "google" from "accounts.google.com")
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2];
            }
            return host;
        } catch (Exception e) {
            return "oidc";
        }
    }

    /**
     * Resolves a username from JWT claims, preferring preferred_username,
     * then falling back to email prefix, then sub.
     */
    private String resolveUsername(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }

        String email = jwt.getClaimAsString("email");
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }

        return jwt.getSubject();
    }
}
