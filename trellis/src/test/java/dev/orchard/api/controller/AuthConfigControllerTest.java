package dev.orchard.api.controller;

import dev.orchard.api.service.CultivatorService;
import dev.orchard.trellis.config.SecurityConfig;
import dev.orchard.trellis.security.CultivatorAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthConfigController.class)
@AutoConfigureMockMvc
@Import({AuthConfigController.class, SecurityConfig.class, CultivatorAuthFilter.class})
@TestPropertySource(properties = {
    "orchard.security.oauth2.enabled=true",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://fence.example.test"
})
class AuthConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CultivatorService cultivatorService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedGet_returnsConfiguredIssuer() throws Exception {
        mockMvc.perform(get("/api/auth/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issuer").value("https://fence.example.test"));
    }
}
