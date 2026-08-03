package dev.orchard.gateway;

import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.auth.FenceTokenClient;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the full gateway application context (as {@code bootRun} would) to catch
 * bean-wiring gaps that plain unit tests miss — e.g. a missing RestClient.Builder
 * autoconfiguration, which the focused FenceTokenClient/TrellisApiClient unit tests
 * bypass entirely by constructing clients directly against a mocked RestClient.
 */
@SpringBootTest
class GatewayApplicationContextTest {

    // SeedlingRelay's @PostConstruct requires a readable internal key at startup;
    // point it at a throwaway generated key rather than the real ~/.ssh path.
    // GroveRelayServer's @PostConstruct starts a real MINA listener, so it must be
    // pointed at an ephemeral port and a temp host-key path too — otherwise this
    // context would try to bind the real 2222 and write to ~/.orchard in CI.
    @DynamicPropertySource
    static void internalSshKey(DynamicPropertyRegistry registry) throws Exception {
        Path keyFile = Files.createTempFile("orchard-gateway-test", "-ed25519");
        try (var out = Files.newOutputStream(keyFile)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
                    new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair(), "test-key", null, out);
        }
        registry.add("orchard.gateway.internal-ssh-key-path", keyFile::toString);
        registry.add("orchard.gateway.ssh-port", () -> 0);
        Path hostKeyDir = Files.createTempDirectory("orchard-gateway-test-hostkey");
        registry.add("orchard.gateway.host-key-path", () -> hostKeyDir.resolve("gateway-host-key").toString());
    }

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
