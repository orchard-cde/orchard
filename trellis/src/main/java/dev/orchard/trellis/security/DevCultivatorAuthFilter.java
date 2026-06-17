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
            @Value("${orchard.dev.default-cultivator-id}") UUID defaultCultivatorId) {
        this.cultivatorService = cultivatorService;
        this.defaultCultivatorId = defaultCultivatorId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getHeader("X-Cultivator-Id") == null) {
            if (ensured.compareAndSet(false, true)) {
                cultivatorService.ensureCultivator(defaultCultivatorId);
            }
            request.setAttribute("cultivatorId", defaultCultivatorId);
        }
        filterChain.doFilter(request, response);
    }
}
