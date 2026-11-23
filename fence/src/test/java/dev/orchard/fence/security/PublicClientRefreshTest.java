package dev.orchard.fence.security;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("standalone")
@AutoConfigureMockMvc
class PublicClientRefreshTest {

    private static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicClientRefreshesWithoutSecretAndRotatesTheRefreshToken() throws Exception {
        String firstRefreshToken = issueRefreshTokenViaDeviceFlow();

        String response = mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", firstRefreshToken)
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.refresh_token").exists())
                .andReturn().getResponse().getContentAsString();
        String rotatedRefreshToken = JsonPath.read(response, "$.refresh_token");
        assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", firstRefreshToken)
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", rotatedRefreshToken)
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk());
    }

    @Test
    void confidentialClientCannotRefreshWithoutAuthenticating() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", "anything")
                        .param("client_id", "orchard-gateway"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void unknownClientIsRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", "anything")
                        .param("client_id", "nobody"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    private String issueRefreshTokenViaDeviceFlow() throws Exception {
        String authorization = mockMvc.perform(post("/oauth2/device_authorization")
                        .contentType("application/x-www-form-urlencoded")
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String deviceCode = JsonPath.read(authorization, "$.device_code");
        String userCode = JsonPath.read(authorization, "$.user_code");

        mockMvc.perform(post("/oauth2/device_verification").param("user_code", userCode))
                .andExpect(status().is3xxRedirection());

        String tokens = mockMvc.perform(post("/oauth2/token")
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", DEVICE_CODE_GRANT)
                        .param("device_code", deviceCode)
                        .param("client_id", "trowel-cli"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(tokens, "$.refresh_token");
    }
}
