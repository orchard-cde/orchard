package dev.orchard.fence.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class StandaloneAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Test
    void trowelCliIsRegistered() {
        assertThat(registeredClientRepository.findByClientId("trowel-cli")).isNotNull();
    }

    @Test
    void deviceVerificationSucceedsWithoutPriorAuthentication() throws Exception {
        MvcResult deviceAuthResult = mockMvc.perform(post("/oauth2/device_authorization")
                        .contentType("application/x-www-form-urlencoded")
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andReturn();
        String responseContent = deviceAuthResult.getResponse().getContentAsString();
        String deviceCode = com.jayway.jsonpath.JsonPath.read(responseContent, "$.device_code");
        String userCode = com.jayway.jsonpath.JsonPath.read(responseContent, "$.user_code");
        assertThat(deviceCode).isNotBlank();
        assertThat(userCode).isNotBlank();

        mockMvc.perform(post("/oauth2/device_verification")
                        .param("user_code", userCode))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .param("device_code", deviceCode)
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists());
    }
}
