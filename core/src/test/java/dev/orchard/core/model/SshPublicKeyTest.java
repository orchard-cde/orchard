package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SshPublicKeyTest {

    private static final String ED25519_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";
    private static final String ED25519_FINGERPRINT = "SHA256:5h8EgYzAsG8Fzte4Vy+j+E9CN2lHRyowxZlFhnUA2Rc";

    private final UUID cultivatorId = UUID.randomUUID();

    @Test
    void register_setsCultivatorIdAndName() {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", ED25519_KEY);

        assertThat(key.cultivatorId()).isEqualTo(cultivatorId);
        assertThat(key.name()).isEqualTo("work-laptop");
    }

    @Test
    void register_generatesUniqueId() {
        SshPublicKey key1 = SshPublicKey.register(cultivatorId, "a", ED25519_KEY);
        SshPublicKey key2 = SshPublicKey.register(cultivatorId, "b", ED25519_KEY);

        assertThat(key1.id()).isNotEqualTo(key2.id());
    }

    @Test
    void register_setsCreatedAt() {
        Instant before = Instant.now();
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", ED25519_KEY);
        Instant after = Instant.now();

        assertThat(key.createdAt()).isBetween(before, after);
    }

    @Test
    void register_computesSha256Fingerprint() {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", ED25519_KEY);

        assertThat(key.fingerprint()).isEqualTo(ED25519_FINGERPRINT);
    }

    @Test
    void register_ignoresTrailingCommentInFingerprint() {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR someone@elsewhere");

        assertThat(key.fingerprint()).isEqualTo(ED25519_FINGERPRINT);
    }

    @Test
    void register_acceptsKeyWithoutComment() {
        SshPublicKey key = SshPublicKey.register(cultivatorId, "bare",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR");

        assertThat(key.fingerprint()).isEqualTo(ED25519_FINGERPRINT);
    }

    @Test
    void register_rejectsBlankName() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SshPublicKey.register(cultivatorId, "  ", ED25519_KEY));
    }

    @Test
    void register_rejectsMalformedKey() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SshPublicKey.register(cultivatorId, "bad", "ssh-ed25519"));
    }

    @Test
    void register_rejectsInvalidBase64() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SshPublicKey.register(cultivatorId, "bad", "ssh-ed25519 !!!not-base64!!!"));
    }

    @Test
    void register_rejectsNullPublicKey() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SshPublicKey.register(cultivatorId, "bad", null));
    }
}
