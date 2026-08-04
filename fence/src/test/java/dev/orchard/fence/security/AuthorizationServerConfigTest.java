package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationServerConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deviceAuthorizationEndpointIsExposed() throws Exception {
        mockMvc.perform(post("/oauth2/device_authorization")
                        .contentType("application/x-www-form-urlencoded")
                        .param("client_id", "orchard-ui"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tokenEndpointIsExposed() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:device_code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jwksEndpointIsExposed() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk());
    }
}
