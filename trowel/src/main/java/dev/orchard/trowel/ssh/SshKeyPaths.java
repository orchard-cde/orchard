package dev.orchard.trowel.ssh;

import java.nio.file.Path;

/** Well-known paths for trowel-managed SSH keys, under ~/.orchard/keys. */
public final class SshKeyPaths {

    private SshKeyPaths() {}

    public static Path keysDir() {
        return Path.of(System.getProperty("user.home"), ".orchard", "keys");
    }

    public static Path privateKey(String name) {
        return keysDir().resolve(name);
    }

    public static Path publicKey(String name) {
        return keysDir().resolve(name + ".pub");
    }
}
