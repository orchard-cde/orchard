package dev.orchard.trowel.ssh;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.UUID;

/**
 * Generates, persists, and loads trowel-managed SSH keypairs. The private key
 * is written in OpenSSH format (interoperable with plain {@code ssh -i}) and
 * locked down to owner-only; the public line is the standard one-line format
 * registered with trellis via {@code ssh-key add}.
 */
public final class SshKeyStore {

    private SshKeyStore() {}

    public static KeyPair loadOrCreate(String name) throws Exception {
        boolean hasPrivate = Files.exists(SshKeyPaths.privateKey(name));
        boolean hasPublic = Files.exists(SshKeyPaths.publicKey(name));

        if (hasPrivate && hasPublic) {
            return load(name);
        }
        if (hasPrivate) {
            // The private-key write and the public-key write are two separate file
            // operations; a process that died in between leaves a valid private key
            // with no .pub file. The OpenSSH private key format embeds the public
            // component, so we can recover the missing file without regenerating
            // (and thus rotating) the key.
            KeyPair keyPair = load(name);
            writePublicKey(keyPair.getPublic(), name);
            return keyPair;
        }

        Files.createDirectories(SshKeyPaths.keysDir());
        // JDK KeyPairGenerator.getInstance("Ed25519") produces an EdECPublicKey that
        // MINA sshd-core cannot encode in OpenSSH format. Going through
        // SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA) routes generation
        // through the net.i2p.crypto:eddsa provider, which sshd-core CAN write/parse.
        KeyPair keyPair = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair();
        write(keyPair, name);
        return keyPair;
    }

    public static KeyPair load(String name) throws Exception {
        return OpenSSHKeyPairResourceParser.INSTANCE
                .loadKeyPairs(null, SshKeyPaths.privateKey(name), null)
                .iterator().next();
    }

    public static void write(KeyPair keyPair, String name) throws IOException, GeneralSecurityException {
        writeAtomically(SshKeyPaths.privateKey(name), true,
                out -> OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, name + "@orchard", null, out));
        writePublicKey(keyPair.getPublic(), name);
    }

    private static void writePublicKey(PublicKey publicKey, String name) throws IOException, GeneralSecurityException {
        String line = PublicKeyEntry.toString(publicKey) + " " + name + "@orchard" + "\n";
        writeAtomically(SshKeyPaths.publicKey(name), false,
                out -> out.write(line.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Writes {@code target} via a sibling temp file that is created owner-only
     * (when {@code ownerOnly} and the filesystem supports POSIX permissions) up
     * front — never via a post-write chmod, which leaves a brief world/group
     * readable window — then atomically renames it into place. A crash mid-write
     * leaves only an orphaned temp file; {@code target} itself is either absent
     * or fully written, never partial.
     */
    private static void writeAtomically(Path target, boolean ownerOnly, ThrowingWriter writer)
            throws IOException, GeneralSecurityException {
        Files.createDirectories(target.getParent());
        Path tempPath = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        boolean posix = target.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix && ownerOnly) {
            Files.createFile(tempPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tempPath);
        }
        try {
            try (OutputStream out = Files.newOutputStream(tempPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write(out);
            }
            Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    @FunctionalInterface
    private interface ThrowingWriter {
        void write(OutputStream out) throws IOException, GeneralSecurityException;
    }
}
