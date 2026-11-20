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

class SecurePathsTest {

    @TempDir
    Path tmp;

    private final SecurePaths paths = SecurePaths.forProcessUser();

    @Test
    void ensurePrivateDirectory_creates_rwx______() throws IOException {
        Path dir = tmp.resolve("a").resolve("b");

        paths.ensurePrivateDirectory(dir);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent())))
                .isEqualTo("rwx------");
    }

    @Test
    void ensurePrivateDirectory_existingPrivate_isAccepted() {
        Path dir = tmp.resolve("ok");
        paths.ensurePrivateDirectory(dir);

        paths.ensurePrivateDirectory(dir);

        assertThat(dir).isDirectory();
    }

    @Test
    void ensurePrivateDirectory_existingPermissive_throws() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("open"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(() -> paths.ensurePrivateDirectory(dir))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("rwx------");
    }

    @Test
    void ensurePrivateDirectory_pathIsFile_throws() throws IOException {
        Path file = Files.createFile(tmp.resolve("file"));

        assertThatThrownBy(() -> paths.ensurePrivateDirectory(file))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("directory");
    }

    @Test
    void ensurePrivateDirectory_symlink_throws() throws IOException {
        Path real = Files.createDirectory(tmp.resolve("real"));
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwx------"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real);

        assertThatThrownBy(() -> paths.ensurePrivateDirectory(link))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void ensurePrivateDirectory_wrongOwner_throws() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("mine"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));

        assertThatThrownBy(() -> asRoot().ensurePrivateDirectory(dir))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("owned by");
    }

    @Test
    void requireRegularFile_matchingModeAndOwner_returnsAttributes() throws IOException {
        Path file = Files.createFile(tmp.resolve("ok"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        assertThat(paths.requireRegularFile(file, "rw-------").isRegularFile()).isTrue();
    }

    @Test
    void requireRegularFile_wrongMode_throws() throws IOException {
        Path file = Files.createFile(tmp.resolve("open"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));

        assertThatThrownBy(() -> paths.requireRegularFile(file, "rw-------"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("rw-------");
    }

    @Test
    void requireRegularFile_wrongOwner_throws() throws IOException {
        Path file = Files.createFile(tmp.resolve("mine"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        assertThatThrownBy(() -> asRoot().requireRegularFile(file, "rw-------"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("owned by");
    }

    private static SecurePaths asRoot() throws IOException {
        return new SecurePaths(FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root"));
    }
}
