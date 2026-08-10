package dev.orchard.gateway.auth;

import dev.orchard.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FenceTokenClientTest {

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.getFence().setIssuerUri("http://localhost:7779");
        p.getOauth2().setClientId("orchard-gateway");
        p.getOauth2().setClientSecret("dev-secret");
        return p;
    }

    @Test
    void fetchesAndCachesToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        FenceTokenClient client = new FenceTokenClient(restClient, properties());

        server.expect(requestTo("http://localhost:7779/oauth2/token"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Basic b3JjaGFyZC1nYXRld2F5OmRldi1zZWNyZXQ="))
                .andRespond(withSuccess(
                        "{\"access_token\":\"jwt-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.accessToken()).isEqualTo("jwt-1");
        assertThat(client.accessToken()).isEqualTo("jwt-1"); // cached, no second request
        server.verify();
    }

    @Test
    void refreshesAfterExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        FenceTokenClient client = new FenceTokenClient(restClient, properties());

        server.expect(requestTo("http://localhost:7779/oauth2/token")).andRespond(withSuccess(
                "{\"access_token\":\"jwt-1\",\"expires_in\":0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:7779/oauth2/token")).andRespond(withSuccess(
                "{\"access_token\":\"jwt-2\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThat(client.accessToken()).isEqualTo("jwt-1");
        assertThat(client.accessToken()).isEqualTo("jwt-2");
        server.verify();
    }
}
