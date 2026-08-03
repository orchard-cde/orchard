package dev.orchard.gateway.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GroveRelayServerTest {

    private static final Path TEST_DIR = Path.of(System.getProperty("java.io.tmpdir"), "orchard-gateway-test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(TEST_DIR);
        // sshd-common's OpenSSH EdDSA encoder requires net.i2p.crypto.eddsa key
        // types; the JDK's own KeyPairGenerator.getInstance("Ed25519") (JEP 339)
        // produces sun.security.ec.ed.EdDSAPrivateKeyImpl, which fails with a
        // ClassCastException in NetI2pCryptoEdDSASupport.getPrivateKeyData — same
        // workaround already used in GatewayApplicationContextTest.
        KeyPair internal = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        Path keyFile = TEST_DIR.resolve("orchard_ed25519");
        try (var out = Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(internal, "test-key", null, out);
        }
        registry.add("orchard.gateway.internal-ssh-key-path", keyFile::toString);
        registry.add("orchard.gateway.host-key-path", () -> TEST_DIR.resolve("gateway-host-key").toString());
        registry.add("orchard.gateway.ssh-port", () -> 0);
        registry.add("orchard.gateway.oauth2.client-secret", () -> "dev-secret");
    }

    @Autowired
    private GroveRelayServer relayServer;

    @Test
    void contextLoadsAndSshListenerIsUp() {
        assertThat(relayServer).isNotNull();
        assertThat(relayServer.getBoundPort()).isGreaterThan(0);
    }
}
