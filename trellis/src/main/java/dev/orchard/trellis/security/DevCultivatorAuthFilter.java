package dev.orchard.trellis.security;

import dev.orchard.api.service.CultivatorService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves a default local-dev cultivator when OAuth2 is disabled, so the bundled
 * orchard-ui authenticates on first load with no client-side setup (issue #78 follow-up).
 * <p>
 * This is the inverse of {@link CultivatorAuthFilter}: it is active only when
 * {@code orchard.security.oauth2.enabled} is false or unset. An explicit
 * {@code X-Cultivator-Id} header still takes precedence (the controllers read the
 * attribute first, then the header), so the attribute is filled in only when no
 * header is present. The default cultivator is ensured once, not per request.
 */
@Component
@ConditionalOnProperty(name = "orchard.security.oauth2.enabled", havingValue = "false", matchIfMissing = true)
public class DevCultivatorAuthFilter extends OncePerRequestFilter {

    private final CultivatorService cultivatorService;
    private final UUID defaultCultivatorId;
    private final AtomicBoolean ensured = new AtomicBoolean(false);

    public DevCultivatorAuthFilter(
            CultivatorService cultivatorService,
            // Default mirrors application-devserver.yml so a non-devserver run with oauth2
            // disabled still starts (the property is only defined in the devserver profile).
            // devserver yml and trowel's --orchard.dev.default-cultivator-id override this.
            @Value("${orchard.dev.default-cultivator-id:11111111-1111-1111-1111-111111111111}") UUID defaultCultivatorId) {
        this.cultivatorService = cultivatorService;
        this.defaultCultivatorId = defaultCultivatorId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getHeader("X-Cultivator-Id") == null) {
            if (ensured.compareAndSet(false, true)) {
                try {
                    cultivatorService.ensureCultivator(defaultCultivatorId);
                } catch (RuntimeException e) {
                    ensured.set(false); // allow a later request to retry the upsert
                    throw e;
                }
            }
            request.setAttribute("cultivatorId", defaultCultivatorId);
        }
        filterChain.doFilter(request, response);
    }
}
