package dev.orchard.gateway.server;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.nio.file.Path;
import java.security.KeyPair;

/** The gateway's own SSH host key, generated on first run and persisted. */
public class HostKeyProvider implements KeyPairProvider {

    private final SimpleGeneratorHostKeyProvider delegate;

    public HostKeyProvider(String path) {
        this.delegate = new SimpleGeneratorHostKeyProvider(Path.of(path));
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
