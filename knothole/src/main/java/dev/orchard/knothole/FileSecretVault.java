package dev.orchard.knothole;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class FileSecretVault implements SecretVault {


    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TEMP_PREFIX = ".tmp-";
    private static final Set<PosixFilePermission> ENTRY_PERMISSIONS = SecurePaths.PRIVATE_FILE;
    private static final Duration STALE_TEMP_AGE = Duration.ofSeconds(60);

    private final Path directory;
    private final MasterKey masterKey;
    private final SecurePaths securePaths;
    private final SecureRandom random = new SecureRandom();

    public FileSecretVault(Path directory, MasterKey masterKey) {
        this(directory, masterKey, SecurePaths.forProcessUser());
    }

    FileSecretVault(Path directory, MasterKey masterKey, SecurePaths securePaths) {
        this.directory = directory;
        this.masterKey = masterKey;
        this.securePaths = securePaths;
        securePaths.ensurePrivateDirectory(directory);
        purge();
    }

    @Override
    public void put(String key, byte[] value) {
        requireValidKey(key);
        if (value.length > MAX_VALUE_BYTES) {
            throw new VaultException("value for '" + key + "' exceeds the 64 KiB limit");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] entry = new byte[NONCE_BYTES + value.length + TAG_BITS / 8];
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            System.arraycopy(nonce, 0, entry, 0, NONCE_BYTES);
            cipher.doFinal(value, 0, value.length, entry, NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new VaultException("cannot encrypt '" + key + "'", e);
        }
        write(key, entry);
    }

    @Override
    public Optional<byte[]> get(String key) {
        requireValidKey(key);
        Path entry = directory.resolve(key);
        byte[] bytes;
        try {
            securePaths.requireRegularFile(entry, ENTRY_PERMISSIONS);
            bytes = Files.readAllBytes(entry);
        } catch (VaultException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return Optional.empty();
            }
            throw e;
        } catch (IOException e) {
            throw new VaultException("cannot read '" + key + "'", e);
        }
        if (bytes.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new VaultException("entry '" + key + "' is truncated");
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOfRange(bytes, 0, NONCE_BYTES));
            return Optional.of(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES));
        } catch (GeneralSecurityException e) {
            throw new VaultException("cannot decrypt '" + key + "'", e);
        }
    }

    @Override
    public boolean delete(String key) {
        requireValidKey(key);
        try {
            return Files.deleteIfExists(directory.resolve(key));
        } catch (IOException e) {
            throw new VaultException("cannot delete '" + key + "'", e);
        }
    }

    @Override
    public Set<String> keys() {
        Set<String> keys = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".") && Files.isRegularFile(entry)) {
                    keys.add(name);
                }
            }
        } catch (IOException e) {
            throw new VaultException("cannot list " + directory, e);
        }
        return keys;
    }

    @Override
    public int purge() {
        Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);
        int removed = 0;
        try (DirectoryStream<Path> temps = Files.newDirectoryStream(directory, TEMP_PREFIX + "*")) {
            for (Path temp : temps) {
                if (deleteIfStale(temp, cutoff)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new VaultException("cannot purge stale temp files in " + directory, e);
        }
        return removed;
    }

    boolean deleteIfStale(Path temp, Instant cutoff) throws IOException {
        try {
            return Files.getLastModifiedTime(temp).toInstant().isBefore(cutoff) && Files.deleteIfExists(temp);
        } catch (NoSuchFileException vanished) {
            return false;
        }
    }

    private void write(String key, byte[] entry) {
        Path temp = directory.resolve(TEMP_PREFIX + key + "-" + Long.toHexString(random.nextLong()));
        try {
            SecurePaths.createPrivateFile(temp, entry, true);
            Files.move(temp, directory.resolve(key), ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new VaultException("cannot write '" + key + "'", e);
        }
    }

    private Cipher cipher(int mode, String key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, masterKey.key(), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(key.getBytes(UTF_8));
        return cipher;
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith(".") || key.indexOf('/') >= 0 || key.indexOf('\0') >= 0) {
            throw new VaultException("invalid key '" + key + "'");
        }
    }
}
