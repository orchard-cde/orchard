package dev.orchard.knothole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSecretVaultTest {

    @TempDir
    Path tmp;

    Path dir;
    MasterKey masterKey;
    FileSecretVault vault;

    @BeforeEach
    void setUp() {
        dir = tmp.resolve("vault");
        masterKey = MasterKey.loadOrCreate(tmp.resolve("keys").resolve("master.key"));
        vault = new FileSecretVault(dir, masterKey);
    }

    @Test
    void put_get_roundTrip() {
        byte[] value = "s3cret".getBytes(StandardCharsets.UTF_8);

        vault.put("a", value);

        assertThat(vault.get("a")).hasValueSatisfying(v -> assertThat(v).isEqualTo(value));
    }

    @Test
    void get_absent_isEmpty() {
        assertThat(vault.get("missing")).isEmpty();
    }

    @Test
    void put_plaintextAbsentFromDirectory() throws IOException {
        byte[] value = ("orchard-plaintext-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        vault.put("a", value);

        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                        .doesNotContain(new String(value, StandardCharsets.ISO_8859_1));
            }
        }
    }

    @Test
    void put_sameValueTwice_differentBytes() throws IOException {
        byte[] value = "same".getBytes(StandardCharsets.UTF_8);

        vault.put("a", value);
        vault.put("b", value);

        assertThat(Files.readAllBytes(dir.resolve("a"))).isNotEqualTo(Files.readAllBytes(dir.resolve("b")));
    }

    @Test
    void get_flippedByte_throws() throws IOException {
        vault.put("a", "value-value".getBytes(StandardCharsets.UTF_8));
        Path entry = dir.resolve("a");
        byte[] bytes = Files.readAllBytes(entry);
        bytes[13] ^= 1;
        Files.write(entry, bytes);

        assertThatThrownBy(() -> vault.get("a"))
                .isInstanceOf(VaultException.class)
                .hasCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void get_entryMovedToOtherKey_throws() throws IOException {
        vault.put("a", "value".getBytes(StandardCharsets.UTF_8));
        Files.move(dir.resolve("a"), dir.resolve("b"));

        assertThatThrownBy(() -> vault.get("b")).isInstanceOf(VaultException.class);
    }

    @Test
    void put_entryPermissions() throws IOException {
        vault.put("a", new byte[] {1});

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("a"))))
                .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
    }

    @Test
    void put_leavesNoTempFile() throws IOException {
        vault.put("a", new byte[] {1});

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".tmp-"));
        }
    }

    @Test
    void constructor_removesStaleTempFiles() throws IOException {
        Path stale = dir.resolve(".tmp-x");
        Files.createFile(stale);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(120)));

        new FileSecretVault(dir, masterKey);

        assertThat(stale).doesNotExist();
    }

    @Test
    void purge_removesStaleTempFiles_keepsFresh() throws IOException {
        Path stale = dir.resolve(".tmp-old");
        Path fresh = dir.resolve(".tmp-new");
        Files.createFile(stale);
        Files.createFile(fresh);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(120)));

        assertThat(vault.purge()).isEqualTo(1);

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
    }

    @Test
    void constructor_wrongOwner_throws() throws IOException {
        SecurePaths asRoot = new SecurePaths(
                FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("root"));

        assertThatThrownBy(() -> new FileSecretVault(dir, masterKey, asRoot)).isInstanceOf(VaultException.class);
    }

    @Test
    void constructor_symlinkedDirectory_throws() throws IOException {
        Path link = tmp.resolve("link");
        Files.createSymbolicLink(link, dir);

        assertThatThrownBy(() -> new FileSecretVault(link, masterKey))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void constructor_existingPermissiveDirectory_throws() throws IOException {
        Path open = Files.createDirectory(tmp.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> new FileSecretVault(open, masterKey)).isInstanceOf(VaultException.class);
    }

    @Test
    void constructor_pathIsFile_throws() throws IOException {
        Path file = Files.createFile(tmp.resolve("file"));

        assertThatThrownBy(() -> new FileSecretVault(file, masterKey)).isInstanceOf(VaultException.class);
    }

    @Test
    void get_symlinkedEntry_throws() throws IOException {
        vault.put("a", "value".getBytes(StandardCharsets.UTF_8));
        Path outside = Files.copy(dir.resolve("a"), tmp.resolve("outside"));
        Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rw-------"));
        Files.createSymbolicLink(dir.resolve("b"), outside);

        assertThatThrownBy(() -> vault.get("b"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void put_over64KiB_throws() {
        assertThatCode(() -> vault.put("ok", new byte[65536])).doesNotThrowAnyException();

        assertThatThrownBy(() -> vault.put("big", new byte[65537]))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("64 KiB");
    }

    @Test
    void keys_listsEntriesOnly() throws IOException {
        vault.put("a", new byte[] {1});
        vault.put("b", new byte[] {2});
        Files.createFile(dir.resolve(".tmp-z"));

        assertThat(vault.keys()).containsExactlyInAnyOrderElementsOf(List.of("a", "b"));
    }

    @Test
    void delete_absent_returnsFalse() {
        assertThat(vault.delete("nope")).isFalse();
    }

    @Test
    void delete_present_returnsTrueAndRemoves() {
        vault.put("a", new byte[] {1});

        assertThat(vault.delete("a")).isTrue();
        assertThat(vault.get("a")).isEmpty();
    }

    @Test
    void put_pathTraversalKey_throws() {
        assertThatThrownBy(() -> vault.put("../escape", new byte[] {1})).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> vault.put(".hidden", new byte[] {1})).isInstanceOf(VaultException.class);
    }
}
