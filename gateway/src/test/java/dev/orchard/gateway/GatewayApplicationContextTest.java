package dev.orchard.gateway;

import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.auth.FenceTokenClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the full gateway application context (as {@code bootRun} would) to catch
 * bean-wiring gaps that plain unit tests miss — e.g. a missing RestClient.Builder
 * autoconfiguration, which the focused FenceTokenClient/TrellisApiClient unit tests
 * bypass entirely by constructing clients directly against a mocked RestClient.
 */
@SpringBootTest
class GatewayApplicationContextTest {

    @Autowired
    private FenceTokenClient fenceTokenClient;

    @Autowired
    private TrellisApiClient trellisApiClient;

    @Test
    void contextLoadsWithHttpClientBeans() {
        assertThat(fenceTokenClient).isNotNull();
        assertThat(trellisApiClient).isNotNull();
    }
}
