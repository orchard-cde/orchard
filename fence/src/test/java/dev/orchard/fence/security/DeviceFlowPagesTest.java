package dev.orchard.fence.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class DeviceFlowPagesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deviceAuthorizationAdvertisesTheActivationPage() throws Exception {
        mockMvc.perform(post("/oauth2/device_authorization")
                        .contentType("application/x-www-form-urlencoded")
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification_uri").value("http://localhost/activate"))
                .andExpect(jsonPath("$.verification_uri_complete")
                        .value(org.hamcrest.Matchers.startsWith("http://localhost/activate?user_code=")));
    }

    @Test
    void bareActivationPageServesACodeEntryForm() throws Exception {
        mockMvc.perform(get("/activate"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("action=\"/oauth2/device_verification\""),
                        org.hamcrest.Matchers.containsString("name=\"user_code\""))));
    }

    @Test
    void activationPageWithCodeForwardsToTheVerificationEndpoint() throws Exception {
        mockMvc.perform(get("/activate").param("user_code", "ABCD-EFGH"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/device_verification?user_code=ABCD-EFGH"));
    }

    @Test
    void deviceVerificationLandsOnASuccessPage() throws Exception {
        mockMvc.perform(get("/").param("success", ""))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("return to your terminal")));
    }

    @Test
    void bareRootIsNotTheSuccessPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Device approved"))));
    }
}
