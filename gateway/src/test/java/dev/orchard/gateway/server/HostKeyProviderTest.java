package dev.orchard.gateway.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class HostKeyProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesAndPersistsHostKeyOnFirstRun() throws Exception {
        Path keyFile = tempDir.resolve("gateway-host-key");

        HostKeyProvider first = new HostKeyProvider(keyFile.toString());
        Iterator<KeyPair> firstKeys = first.loadKeys(null).iterator();
        assertThat(firstKeys.hasNext()).isTrue();
        assertThat(firstKeys.next().getPublic().getAlgorithm()).isEqualTo("EdDSA");
        assertThat(Files.exists(keyFile)).isTrue();

        HostKeyProvider second = new HostKeyProvider(keyFile.toString());
        Iterator<KeyPair> secondKeys = second.loadKeys(null).iterator();
        assertThat(secondKeys.hasNext()).isTrue();
        assertThat(secondKeys.next().getPublic()).isEqualTo(firstKeysHasPublic(first));
    }

    private java.security.PublicKey firstKeysHasPublic(HostKeyProvider provider) throws Exception {
        return provider.loadKeys(null).iterator().next().getPublic();
    }
}
