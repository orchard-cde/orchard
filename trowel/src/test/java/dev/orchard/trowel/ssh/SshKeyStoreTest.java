package dev.orchard.trowel.ssh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyStoreTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void loadOrCreate_generatesKeypairAndWritesFiles() throws Exception {
        KeyPair pair = SshKeyStore.loadOrCreate("default");

        assertThat(pair.getPrivate()).isNotNull();
        assertThat(SshKeyPaths.privateKey("default")).exists();
        assertThat(SshKeyPaths.publicKey("default")).exists();
        assertThat(Files.readString(SshKeyPaths.publicKey("default"))).startsWith("ssh-ed25519 ");
        assertThat(Files.getPosixFilePermissions(SshKeyPaths.privateKey("default")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void loadOrCreate_isIdempotent() throws Exception {
        KeyPair first = SshKeyStore.loadOrCreate("default");
        KeyPair second = SshKeyStore.loadOrCreate("default");

        assertThat(second.getPublic()).isEqualTo(first.getPublic());
    }

    @Test
    void load_readsBackWrittenKey() throws Exception {
        KeyPair created = SshKeyStore.loadOrCreate("default");
        KeyPair loaded = SshKeyStore.load("default");

        assertThat(loaded.getPublic()).isEqualTo(created.getPublic());
    }

    @Test
    void publicLine_isFingerprintable() throws Exception {
        SshKeyStore.loadOrCreate("default");
        String publicLine = Files.readString(SshKeyPaths.publicKey("default")).trim();

        assertThat(dev.orchard.core.model.SshPublicKey.fingerprint(publicLine)).startsWith("SHA256:");
    }
}
