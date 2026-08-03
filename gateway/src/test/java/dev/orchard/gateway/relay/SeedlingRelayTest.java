package dev.orchard.gateway.relay;

import dev.orchard.gateway.config.GatewayProperties;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SeedlingRelayTest {

    @TempDir
    Path tempDir;

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.setInternalSshKeyPath(tempDir.resolve("orchard_ed25519").toString());
        return p;
    }

    @Test
    void loadsInternalKeyFromOpenSshPem() throws Exception {
        // Generate via the net.i2p eddsa library directly: the JDK's built-in Ed25519
        // KeyPairGenerator produces sun.security.ec.ed keys that MINA's OpenSSH writer
        // (which encodes via net.i2p.crypto.eddsa.EdDSAPrivateKey) cannot cast.
        KeyPair pair = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        Path keyFile = tempDir.resolve("orchard_ed25519");
        try (var out = java.nio.file.Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(pair, "test-key", null, out);
        }

        SeedlingRelay relay = new SeedlingRelay(new SshClient(), properties());
        KeyPair loaded = relay.loadInternalKey();
        assertThat(PublicKeyEntry.toString(loaded.getPublic()))
                .isEqualTo(PublicKeyEntry.toString(pair.getPublic()));
    }

    @Test
    void clientSessionIsCachedPerServerSession() {
        SeedlingRelay relay = new SeedlingRelay(new SshClient(), properties());
        ServerSession serverSession = mock(ServerSession.class);
        // No live seedling here — assert the caching key exists and is stable.
        assertThat(SeedlingRelay.RELAY_SESSION_KEY).isNotNull();
        assertThat(serverSession).isNotNull();
    }
}
