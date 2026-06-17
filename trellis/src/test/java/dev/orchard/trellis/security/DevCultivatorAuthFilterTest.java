package dev.orchard.trellis.security;

import dev.orchard.api.service.CultivatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DevCultivatorAuthFilterTest {

    private static final UUID DEFAULT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void setsDefaultCultivatorWhenNoHeader() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        DevCultivatorAuthFilter filter = new DevCultivatorAuthFilter(service, DEFAULT_ID);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute("cultivatorId")).isEqualTo(DEFAULT_ID);
        verify(service).ensureCultivator(DEFAULT_ID);
        assertThat(chain.getRequest()).isSameAs(request); // chain continued
    }

    @Test
    void doesNotOverrideExplicitHeader() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        DevCultivatorAuthFilter filter = new DevCultivatorAuthFilter(service, DEFAULT_ID);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Cultivator-Id", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute("cultivatorId")).isNull();
        verify(service, never()).ensureCultivator(any());
    }

    @Test
    void ensuresCultivatorOnlyOnceAcrossRequests() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        DevCultivatorAuthFilter filter = new DevCultivatorAuthFilter(service, DEFAULT_ID);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        verify(service, times(1)).ensureCultivator(DEFAULT_ID);
    }

    @Test
    void retriesEnsureWhenFirstAttemptThrows() throws Exception {
        CultivatorService service = mock(CultivatorService.class);
        when(service.ensureCultivator(DEFAULT_ID))
            .thenThrow(new RuntimeException("transient DB error"))
            .thenReturn(null);
        DevCultivatorAuthFilter filter = new DevCultivatorAuthFilter(service, DEFAULT_ID);

        // First request: ensureCultivator throws, flag is reset
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        try {
            filter.doFilter(req1, new MockHttpServletResponse(), new MockFilterChain());
        } catch (RuntimeException ignored) {
            // expected
        }

        // Second request: flag was reset, so ensureCultivator is called again and succeeds
        MockHttpServletRequest req2 = new MockHttpServletRequest();
        filter.doFilter(req2, new MockHttpServletResponse(), new MockFilterChain());

        verify(service, times(2)).ensureCultivator(DEFAULT_ID);
        assertThat(req2.getAttribute("cultivatorId")).isEqualTo(DEFAULT_ID);
    }

    @Test
    void beanPresentWhenOauthDisabledAndAbsentWhenEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CultivatorService.class, () -> mock(CultivatorService.class))
            .withPropertyValues("orchard.dev.default-cultivator-id=11111111-1111-1111-1111-111111111111")
            .withUserConfiguration(DevCultivatorAuthFilter.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(DevCultivatorAuthFilter.class)); // matchIfMissing
        runner.withPropertyValues("orchard.security.oauth2.enabled=false")
            .run(ctx -> assertThat(ctx).hasSingleBean(DevCultivatorAuthFilter.class));
        runner.withPropertyValues("orchard.security.oauth2.enabled=true")
            .run(ctx -> assertThat(ctx).doesNotHaveBean(DevCultivatorAuthFilter.class));

        // Confirm the gate annotation is declared as designed
        ConditionalOnProperty gate = DevCultivatorAuthFilter.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(gate).isNotNull();
        assertThat(gate.name()).contains("orchard.security.oauth2.enabled");
        assertThat(gate.havingValue()).isEqualTo("false");
        assertThat(gate.matchIfMissing()).isTrue();
    }
}
