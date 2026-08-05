package dev.orchard.nursery.qemu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QemuSshAuthorizedKeysTest {

    private static final String SHARED_KEY = "ssh-ed25519 AAAAshared trellis@orchard";
    private static final String REGISTERED_KEY_1 = "ssh-ed25519 AAAAreg1 cultivator@orchard";
    private static final String REGISTERED_KEY_2 = "ssh-rsa AAAAreq2 cultivator@laptop";

    @Test
    void block_includesConfiguredKeyAndRegisteredKeys() {
        String block = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(
            SHARED_KEY, List.of(REGISTERED_KEY_1, REGISTERED_KEY_2));

        assertThat(block)
            .contains("    ssh_authorized_keys:")
            .contains("      - " + SHARED_KEY)
            .contains("      - " + REGISTERED_KEY_1)
            .contains("      - " + REGISTERED_KEY_2);
    }

    @Test
    void block_includesOnlyConfiguredKeyWhenNoRegisteredKeys() {
        String block = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(SHARED_KEY, List.of());

        assertThat(block)
            .contains("      - " + SHARED_KEY)
            .doesNotContain("ssh-rsa");
    }

    @Test
    void block_includesRegisteredKeysWhenNoConfiguredKey() {
        String block = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(null, List.of(REGISTERED_KEY_1));

        assertThat(block)
            .doesNotContain("trellis@orchard")
            .contains("      - " + REGISTERED_KEY_1);
    }

    @Test
    void block_isEmptyWhenNoKeysAtAll() {
        String block = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(null, List.of());

        assertThat(block).isEmpty();
    }

    @Test
    void block_ignoresBlankRegisteredKeys() {
        String block = QemuSeedlingProvider.buildSshAuthorizedKeysBlock(SHARED_KEY, List.of("", "   "));

        assertThat(block)
            .contains("      - " + SHARED_KEY)
            .doesNotContain("ssh-rsa");
    }
}
