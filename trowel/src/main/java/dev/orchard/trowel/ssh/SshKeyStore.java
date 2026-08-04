package dev.orchard.trowel.ssh;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

/**
 * Generates, persists, and loads trowel-managed SSH keypairs. The private key
 * is written in OpenSSH format (interoperable with plain {@code ssh -i}) and
 * locked down to owner-only; the public line is the standard one-line format
 * registered with trellis via {@code ssh-key add}.
 */
public final class SshKeyStore {

    private SshKeyStore() {}

    public static KeyPair loadOrCreate(String name) throws Exception {
        if (Files.exists(SshKeyPaths.privateKey(name))) {
            return load(name);
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
        var privatePath = SshKeyPaths.privateKey(name);
        Files.createDirectories(privatePath.getParent());
        try (var out = Files.newOutputStream(privatePath)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, name + "@orchard", null, out);
        }
        Files.setPosixFilePermissions(privatePath, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(SshKeyPaths.publicKey(name), PublicKeyEntry.toString(keyPair.getPublic()) + "\n");
    }
}
