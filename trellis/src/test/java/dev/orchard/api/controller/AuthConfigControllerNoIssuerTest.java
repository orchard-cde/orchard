package dev.orchard.api.controller;

import dev.orchard.trellis.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthConfigController.class)
@AutoConfigureMockMvc
@Import({AuthConfigController.class, SecurityConfig.class})
@TestPropertySource(properties = "orchard.security.oauth2.enabled=false")
class AuthConfigControllerNoIssuerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedGet_returnsNullIssuer() throws Exception {
        mockMvc.perform(get("/api/auth/config"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"issuer\":null}", true));
    }
}
