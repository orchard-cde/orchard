package dev.orchard.gateway.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    @Test
    void createsMissingParentDirectoriesOnFirstRun() throws Exception {
        // Mirrors the real default (~/.orchard/gateway-host-key): the parent directory
        // doesn't exist yet on a clean host, so the provider must create it rather than
        // let SimpleGeneratorHostKeyProvider fail with NoSuchFileException.
        Path keyFile = tempDir.resolve("nested").resolve("orchard").resolve("gateway-host-key");
        assertThat(Files.exists(keyFile.getParent())).isFalse();

        HostKeyProvider provider = new HostKeyProvider(keyFile.toString());
        Iterator<KeyPair> keys = provider.loadKeys(null).iterator();

        assertThat(keys.hasNext()).isTrue();
        assertThat(Files.exists(keyFile)).isTrue();
    }

    @Test
    void generatedHostKeyFileIsOwnerOnly() throws Exception {
        Path keyFile = tempDir.resolve("gateway-host-key");
        assumeTrue(keyFile.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions not supported on this filesystem");

        HostKeyProvider provider = new HostKeyProvider(keyFile.toString());
        provider.loadKeys(null).iterator().next();

        assertThat(Files.getPosixFilePermissions(keyFile))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    private java.security.PublicKey firstKeysHasPublic(HostKeyProvider provider) throws Exception {
        return provider.loadKeys(null).iterator().next().getPublic();
    }
}
