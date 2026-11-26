package dev.orchard.knothole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterKeyTest {

    @TempDir
    Path tmp;

    @Test
    void loadOrCreate_missingFile_createsOwnerOnlyKeyFile() throws IOException {
        Path path = tmp.resolve("keys").resolve("master.key");

        MasterKey.loadOrCreate(path);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path.getParent())))
                .isEqualTo("rwx------");
        assertThat(Files.size(path)).isEqualTo(32);
    }

    @Test
    void loadOrCreate_existingFile_loadsSameKey() {
        Path path = tmp.resolve("keys").resolve("master.key");

        MasterKey first = MasterKey.loadOrCreate(path);
        MasterKey second = MasterKey.loadOrCreate(path);

        assertThat(second.key().getEncoded()).isEqualTo(first.key().getEncoded());
        assertThat(first.key().getAlgorithm()).isEqualTo("AES");
        assertThat(first.key().getEncoded()).hasSize(32);
    }

    @Test
    void loadOrCreate_wrongSize_throws() throws IOException {
        Path path = writeKeyFile(tmp.resolve("short.key"), 16, "rw-------");

        assertThatThrownBy(() -> MasterKey.loadOrCreate(path))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void loadOrCreate_tooPermissive_throws() throws IOException {
        Path path = writeKeyFile(tmp.resolve("open.key"), 32, "rw-r--r--");

        assertThatThrownBy(() -> MasterKey.loadOrCreate(path))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("rw-------");
    }

    @Test
    void loadOrCreate_symlink_throws() throws IOException {
        Path real = writeKeyFile(tmp.resolve("real.key"), 32, "rw-------");
        Path link = tmp.resolve("link.key");
        Files.createSymbolicLink(link, real);

        assertThatThrownBy(() -> MasterKey.loadOrCreate(link))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void loadOrCreate_wrongOwner_throws() throws IOException {
        Path path = writeKeyFile(tmp.resolve("owned.key"), 32, "rw-------");
        SecurePaths asRoot = new SecurePaths(
                FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root"));

        assertThatThrownBy(() -> MasterKey.loadOrCreate(path, asRoot))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("owned by");
    }

    private static Path writeKeyFile(Path path, int size, String permissions) throws IOException {
        Files.write(path, new byte[size]);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        return path;
    }
}
