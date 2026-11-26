package dev.orchard.knothole;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

final class SecurePaths {

    static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(OWNER_READ, OWNER_WRITE);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_FILE_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(PRIVATE_FILE);
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY_ATTRIBUTE =
            PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY);

    private final UserPrincipal expectedOwner;

    SecurePaths(UserPrincipal expectedOwner) {
        this.expectedOwner = expectedOwner;
    }

    static SecurePaths forProcessUser() {
        String name = System.getProperty("user.name");
        try {
            return new SecurePaths(FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(name));
        } catch (IOException e) {
            throw new VaultException("cannot resolve process user '" + name + "'", e);
        }
    }

    void ensurePrivateDirectory(Path dir) {
        try {
            createMissing(dir);
            PosixFileAttributes attrs = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                throw new VaultException(dir + " is a symlink; a real directory is required");
            }
            if (!attrs.isDirectory()) {
                throw new VaultException(dir + " is not a directory");
            }
            requireModeAndOwner(dir, attrs, PRIVATE_DIRECTORY);
        } catch (IOException e) {
            throw new VaultException("cannot prepare directory " + dir, e);
        }
    }

    PosixFileAttributes requireRegularFile(Path file, Set<PosixFilePermission> permissions) {
        PosixFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new VaultException("cannot read " + file, e);
        }
        if (attrs.isSymbolicLink()) {
            throw new VaultException(file + " is a symlink; a regular file is required");
        }
        if (!attrs.isRegularFile()) {
            throw new VaultException(file + " is not a regular file");
        }
        requireModeAndOwner(file, attrs, permissions);
        return attrs;
    }

    static void createPrivateFile(Path path, byte[] bytes, boolean fsync) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, Set.of(CREATE_NEW, WRITE), PRIVATE_FILE_ATTRIBUTE);
        try (channel) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            if (fsync && channel instanceof FileChannel fileChannel) {
                fileChannel.force(true);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private void createMissing(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path parent = dir.toAbsolutePath().getParent();
        if (parent != null) {
            createMissing(parent);
        }
        try {
            Files.createDirectory(dir, PRIVATE_DIRECTORY_ATTRIBUTE);
        } catch (FileAlreadyExistsException ignored) {
            // lost a creation race; the caller validates what is there
        }
    }

    private void requireModeAndOwner(Path path, PosixFileAttributes attrs, Set<PosixFilePermission> permissions) {
        if (!attrs.permissions().equals(permissions)) {
            throw new VaultException(path + " has mode " + PosixFilePermissions.toString(attrs.permissions())
                    + "; it must be exactly " + PosixFilePermissions.toString(permissions));
        }
        if (!attrs.owner().equals(expectedOwner)) {
            throw new VaultException(path + " is owned by " + attrs.owner().getName()
                    + "; it must be owned by " + expectedOwner.getName());
        }
    }
}
