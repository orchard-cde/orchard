package dev.orchard.api.gateway;

import dev.orchard.core.model.SshPublicKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the SSH gateway must fingerprint offered keys with the same
 * algorithm as key registration. Lives outside dev.orchard.core.model to prove
 * SshPublicKey.fingerprint is public API (the gateway module calls it).
 */
class SshPublicKeyFingerprintContractTest {

    private static final String ED25519_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";
    private static final String ED25519_FINGERPRINT = "SHA256:5h8EgYzAsG8Fzte4Vy+j+E9CN2lHRyowxZlFhnUA2Rc";

    @Test
    void fingerprint_isCallableFromOtherPackages() {
        assertThat(SshPublicKey.fingerprint(ED25519_KEY)).isEqualTo(ED25519_FINGERPRINT);
    }
}
