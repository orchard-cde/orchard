package dev.orchard.knothole;

import java.io.IOException;
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

    private static final String PRIVATE_DIRECTORY = "rwx------";

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

    PosixFileAttributes requireRegularFile(Path file, String permissions) {
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

    private void createMissing(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path parent = dir.toAbsolutePath().getParent();
        if (parent != null) {
            createMissing(parent);
        }
        try {
            Files.createDirectory(dir, privateAttribute());
        } catch (FileAlreadyExistsException ignored) {
            // lost a creation race; the caller validates what is there
        }
    }

    private void requireModeAndOwner(Path path, PosixFileAttributes attrs, String permissions) {
        String actual = PosixFilePermissions.toString(attrs.permissions());
        if (!actual.equals(permissions)) {
            throw new VaultException(path + " has mode " + actual + "; it must be exactly " + permissions);
        }
        if (!attrs.owner().equals(expectedOwner)) {
            throw new VaultException(path + " is owned by " + attrs.owner().getName()
                    + "; it must be owned by " + expectedOwner.getName());
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> privateAttribute() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(PRIVATE_DIRECTORY));
    }
}
