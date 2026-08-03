package dev.orchard.trellis.security;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.core.model.Cultivator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CultivatorAuthFilterTest {

    private static Jwt jwtWith(String subject, String email) {
        return jwtWith(subject, email, null, null);
    }

    private static Jwt jwtWith(String subject, String email, String picture, String name) {
        var builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(subject);
        if (email != null) {
            builder = builder.claim("email", email);
        }
        if (picture != null) {
            builder = builder.claim("picture", picture);
        }
        if (name != null) {
            builder = builder.claim("name", name);
        }
        return builder.build();
    }

    @Test
    void skipsCultivatorResolutionWhenJwtHasNoEmailClaim() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        CultivatorAuthFilter filter = new CultivatorAuthFilter(service);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwtWith("orchard-gateway", null)));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(service);
        assertThat(request.getAttribute("cultivatorId")).isNull();
        assertThat(chain.getRequest()).isSameAs(request); // chain continued
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesCultivatorWhenJwtHasEmailClaim() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        CultivatorAuthFilter filter = new CultivatorAuthFilter(service);
        UUID cultivatorId = UUID.randomUUID();
        when(service.findOrCreateCultivator(
                "google", "goog-123", "alice@example.com", "alice@example.com", "https://pic", "Alice"))
            .thenReturn(new Cultivator(cultivatorId, "alice@example.com", "alice@example.com",
                "google", "goog-123", "https://pic", "Alice", Instant.now(), Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwtWith("goog-123", "alice@example.com", "https://pic", "Alice")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(service).findOrCreateCultivator(
            "google", "goog-123", "alice@example.com", "alice@example.com", "https://pic", "Alice");
        assertThat(request.getAttribute("cultivatorId")).isEqualTo(cultivatorId);
        SecurityContextHolder.clearContext();
    }
}
