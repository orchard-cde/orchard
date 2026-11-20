package dev.orchard.knothole;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class MasterKey {

    private static final int KEY_BYTES = 32;
    private static final String FILE_MODE = "rw-------";

    private final SecretKey key;

    private MasterKey(byte[] bytes) {
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public static MasterKey loadOrCreate(Path path) {
        return loadOrCreate(path, SecurePaths.forProcessUser());
    }

    static MasterKey loadOrCreate(Path path, SecurePaths securePaths) {
        Path absolute = path.toAbsolutePath();
        securePaths.ensurePrivateDirectory(absolute.getParent());
        try {
            return create(absolute);
        } catch (FileAlreadyExistsException exists) {
            return load(absolute, securePaths);
        } catch (IOException e) {
            throw new VaultException("cannot create master key " + absolute, e);
        }
    }

    public SecretKey key() {
        return key;
    }

    private static MasterKey create(Path path) throws IOException {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        SeekableByteChannel channel = Files.newByteChannel(path, Set.of(CREATE_NEW, WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)));
        try (channel) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        }
        return new MasterKey(bytes);
    }

    private static MasterKey load(Path path, SecurePaths securePaths) {
        long size = securePaths.requireRegularFile(path, FILE_MODE).size();
        if (size != KEY_BYTES) {
            throw new VaultException("master key " + path + " is " + size + " bytes; it must be exactly " + KEY_BYTES + " bytes");
        }
        try {
            return new MasterKey(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new VaultException("cannot read master key " + path, e);
        }
    }
}
