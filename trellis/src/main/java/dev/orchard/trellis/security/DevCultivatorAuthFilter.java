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

@Component
@ConditionalOnProperty(name = "orchard.security.oauth2.enabled", havingValue = "false", matchIfMissing = true)
public class DevCultivatorAuthFilter extends OncePerRequestFilter {

    private final CultivatorService cultivatorService;
    private final UUID defaultCultivatorId;
    private final AtomicBoolean ensured = new AtomicBoolean(false);

    public DevCultivatorAuthFilter(
            CultivatorService cultivatorService,
            @Value("${orchard.dev.default-cultivator-id:11111111-1111-1111-1111-111111111111}") UUID defaultCultivatorId) {
        this.cultivatorService = cultivatorService;
        this.defaultCultivatorId = defaultCultivatorId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (ensured.compareAndSet(false, true)) {
            try {
                cultivatorService.ensureCultivator(defaultCultivatorId);
            } catch (RuntimeException e) {
                ensured.set(false);
                throw e;
            }
        }
        request.setAttribute("cultivatorId", defaultCultivatorId);
        filterChain.doFilter(request, response);
    }
}
