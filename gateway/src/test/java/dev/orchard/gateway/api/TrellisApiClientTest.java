package dev.orchard.gateway.api;

import dev.orchard.gateway.auth.FenceTokenClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TrellisApiClientTest {

    private FenceTokenClient tokenClient;
    private TrellisApiClient client;
    private MockRestServiceServer server;

    void setUp(String baseUrl) {
        tokenClient = mock(FenceTokenClient.class);
        when(tokenClient.accessToken()).thenReturn("svc-token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = TrellisApiClient.buildRestClient(builder, baseUrl, tokenClient);
        client = new TrellisApiClient(restClient, new tools.jackson.databind.ObjectMapper());
    }

    @Test
    void resolveGrove_returnsRoute() {
        setUp("http://trellis:8080");
        UUID groveId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(
                        "{\"groveId\":\"" + groveId + "\",\"cultivatorId\":\"%s\",\"seedlingIp\":\"127.0.0.1\",\"seedlingPort\":22,\"state\":\"FLOURISHING\"}"
                                .formatted(UUID.randomUUID()),
                        APPLICATION_JSON));

        Optional<GatewayRoute> route = client.resolveGrove(groveId);
        assertThat(route).isPresent();
        assertThat(route.get().groveId()).isEqualTo(groveId);
        assertThat(route.get().seedlingIp()).isEqualTo("127.0.0.1");
        assertThat(route.get().seedlingPort()).isEqualTo(22);
    }

    @Test
    void resolveGrove_unknownAndNotReadyAreEmpty() {
        setUp("http://trellis:8080");
        UUID groveId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo("http://trellis:8080/api/gateway/groves/" + groveId))
                .andRespond(withStatus(CONFLICT));

        assertThat(client.resolveGrove(groveId)).isEmpty();
        assertThat(client.resolveGrove(groveId)).isEmpty();
        server.verify();
    }

    @Test
    void authorizeOwner_returnsRouteForOwner() throws Exception {
        setUp("http://trellis:8080");
        UUID groveId = UUID.randomUUID();
        GatewayRoute expected = new GatewayRoute(groveId, UUID.randomUUID(), "127.0.0.1", 22, "FLOURISHING");
        server.expect(requestTo("http://trellis:8080/api/gateway/authorize-owner"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"groveId\":\"" + groveId + "\",\"email\":\"alice@example.com\"}"))
                .andRespond(withSuccess(
                        new tools.jackson.databind.ObjectMapper().writeValueAsString(expected),
                        APPLICATION_JSON));

        assertThat(client.authorizeOwner(groveId, "alice@example.com")).contains(expected);
    }

    @Test
    void authorizeOwner_notOwnerOrUnknown_returnsEmpty() {
        setUp("http://trellis:8080");
        server.expect(requestTo("http://trellis:8080/api/gateway/authorize-owner"))
                .andExpect(method(POST))
                .andRespond(withStatus(FORBIDDEN));

        assertThat(client.authorizeOwner(UUID.randomUUID(), "bob@example.com")).isEmpty();
    }

    @Test
    void listKeys_parsesArray() {
        setUp("http://trellis:8080");
        UUID cultivatorId = UUID.randomUUID();
        server.expect(requestTo("http://trellis:8080/api/gateway/cultivators/" + cultivatorId + "/keys"))
                .andRespond(withSuccess(
                        "[{\"id\":\"%s\",\"name\":\"laptop\",\"publicKey\":\"ssh-ed25519 AAAA test@orchard.dev\",\"fingerprint\":\"SHA256:abc\"}]"
                                .formatted(UUID.randomUUID()),
                        APPLICATION_JSON));

        List<GatewayKey> keys = client.listKeys(cultivatorId);
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).name()).isEqualTo("laptop");
        assertThat(keys.get(0).fingerprint()).isEqualTo("SHA256:abc");
    }
}
