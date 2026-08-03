package dev.orchard.api.controller;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.api.service.GatewayGroveService;
import dev.orchard.trellis.config.SecurityConfig;
import dev.orchard.trellis.security.CultivatorAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real {@link SecurityConfig} secured filter chain against
 * /api/gateway/** (finding I1 hardening): only tokens carrying the dedicated
 * "gateway" scope may call this internal API, not any authenticated user.
 */
@WebMvcTest(GatewayGroveController.class)
@AutoConfigureMockMvc
@Import({GatewayGroveController.class, GlobalExceptionHandler.class, SecurityConfig.class, CultivatorAuthFilter.class})
@TestPropertySource(properties = "orchard.security.oauth2.enabled=true")
class GatewayGroveControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GatewayGroveService gatewayGroveService;

    @MockitoBean
    private CultivatorService cultivatorService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void noAuth_returns401() throws Exception {
        UUID groveId = UUID.randomUUID();

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenWithOnlyOpenidScope_returns403() throws Exception {
        UUID groveId = UUID.randomUUID();

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_openid"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void gatewayScopedToken_isAuthorized() throws Exception {
        UUID groveId = UUID.randomUUID();
        when(gatewayGroveService.resolveRoute(groveId)).thenReturn(Optional.empty());
        when(gatewayGroveService.exists(groveId)).thenReturn(false);

        mockMvc.perform(get("/api/gateway/groves/{groveId}", groveId)
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_gateway"))))
            .andExpect(status().isNotFound());
    }
}
