package dev.orchard.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretTest {

    @Test
    void store_validName_setsEqualTimestamps() {
        UUID cultivatorId = UUID.randomUUID();

        Secret secret = Secret.store(cultivatorId, "API_TOKEN", "vk-1");

        assertThat(secret.id()).isNotNull();
        assertThat(secret.scope()).isEqualTo(SecretScope.CULTIVATOR);
        assertThat(secret.cultivatorId()).isEqualTo(cultivatorId);
        assertThat(secret.owner()).isNull();
        assertThat(secret.name()).isEqualTo("API_TOKEN");
        assertThat(secret.vaultKey()).isEqualTo("vk-1");
        assertThat(secret.updatedAt()).isEqualTo(secret.createdAt());
        assertThat(secret.rotatedAt()).isEqualTo(secret.createdAt());
    }

    @Test
    void store_invalidName_throws() {
        UUID cultivatorId = UUID.randomUUID();

        assertThatThrownBy(() -> Secret.store(cultivatorId, "my-token", "vk")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Secret.store(cultivatorId, "", "vk")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Secret.store(cultivatorId, "a".repeat(256), "vk")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void store_reservedName_throws() {
        UUID cultivatorId = UUID.randomUUID();

        for (String name : new String[] {"PATH", "IFS", "ENV", "BASH_ENV", "LD_PRELOAD", "LD_LIBRARY_PATH", "HOME", "SHELL"}) {
            assertThatThrownBy(() -> Secret.store(cultivatorId, name, "vk"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reserved name: " + name);
            assertThat(Secret.isStorableName(name)).isFalse();
        }
        assertThat(Secret.store(cultivatorId, "PATH_EXTRA", "vk").name()).isEqualTo("PATH_EXTRA");
        assertThat(Secret.isStorableName("path")).isTrue();
    }

    @Test
    void withValueWritten_bumpsRotatedAndUpdated_keepsCreated() {
        Secret original = Secret.store(UUID.randomUUID(), "TOKEN", "vk-1");
        Instant later = original.createdAt().plusSeconds(60);

        Secret rotated = original.withValueWritten("vk-2", later);

        assertThat(rotated.vaultKey()).isEqualTo("vk-2");
        assertThat(rotated.updatedAt()).isEqualTo(later);
        assertThat(rotated.rotatedAt()).isEqualTo(later);
        assertThat(rotated.createdAt()).isEqualTo(original.createdAt());
        assertThat(rotated.id()).isEqualTo(original.id());
        assertThat(rotated.name()).isEqualTo(original.name());
        assertThat(rotated.cultivatorId()).isEqualTo(original.cultivatorId());
    }
}
