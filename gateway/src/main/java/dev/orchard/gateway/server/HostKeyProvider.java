package dev.orchard.gateway.server;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

/** The gateway's own SSH host key, generated on first run and persisted. */
public class HostKeyProvider implements KeyPairProvider {

    private final SimpleGeneratorHostKeyProvider delegate;

    public HostKeyProvider(String path) {
        Path keyPath = Path.of(path);
        // SimpleGeneratorHostKeyProvider.doWriteKeyPair opens the file via
        // Files.newOutputStream without creating parent directories first. The default
        // path (~/.orchard/gateway-host-key) doesn't exist on a clean host, so first-run
        // @PostConstruct generation throws NoSuchFileException unless we create it here.
        Path parent = keyPath.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create host key directory " + parent, e);
            }
        }
        this.delegate = new SimpleGeneratorHostKeyProvider(keyPath);
        // AbstractGeneratorHostKeyProvider.generateKeyPair(algorithm) passes this
        // string straight to SecurityUtils.getKeyPairGenerator(algorithm), i.e. a
        // JCA KeyPairGenerator algorithm name ("EdDSA") — not the SSH wire key-type
        // identifier ("ssh-ed25519", KeyPairProvider.SSH_ED25519), which fails with
        // NoSuchAlgorithmException.
        this.delegate.setAlgorithm(SecurityUtils.EDDSA);
    }

    @Override
    public Iterable<KeyPair> loadKeys(SessionContext session) {
        return delegate.loadKeys(session);
    }
}
