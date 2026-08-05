package dev.orchard.api.service;

import dev.orchard.core.model.SshPublicKey;
import dev.orchard.roots.entity.SshPublicKeyEntity;
import dev.orchard.roots.repository.SshPublicKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SshPublicKeyServiceTest {

    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev";

    @Mock
    private SshPublicKeyRepository repository;

    @Mock
    private CultivatorService cultivatorService;

    private SshPublicKeyService service;

    @BeforeEach
    void setUp() {
        service = new SshPublicKeyService(repository, cultivatorService);
    }

    @Test
    void register_ensuresCultivatorAndSavesKey() {
        UUID cultivatorId = UUID.randomUUID();

        SshPublicKey result = service.register(cultivatorId, "work-laptop", KEY);

        verify(cultivatorService).ensureCultivator(cultivatorId);
        ArgumentCaptor<SshPublicKeyEntity> captor = ArgumentCaptor.forClass(SshPublicKeyEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCultivatorId()).isEqualTo(cultivatorId);
        assertThat(captor.getValue().getName()).isEqualTo("work-laptop");
        assertThat(result.id()).isEqualTo(captor.getValue().getId());
        assertThat(result.fingerprint()).isEqualTo("SHA256:5h8EgYzAsG8Fzte4Vy+j+E9CN2lHRyowxZlFhnUA2Rc");
    }

    @Test
    void register_blankName_throws() {
        assertThatThrownBy(() -> service.register(UUID.randomUUID(), "  ", KEY))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void register_blankKey_throws() {
        assertThatThrownBy(() -> service.register(UUID.randomUUID(), "work-laptop", "  "))
            .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listForCultivator_returnsKeys() {
        UUID cultivatorId = UUID.randomUUID();
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", KEY);
        when(repository.findByCultivatorId(cultivatorId))
            .thenReturn(List.of(SshPublicKeyEntity.fromModel(key)));

        List<SshPublicKey> keys = service.listForCultivator(cultivatorId);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).id()).isEqualTo(key.id());
        assertThat(keys.get(0).fingerprint()).isEqualTo(key.fingerprint());
    }

    @Test
    void listForCultivator_empty() {
        UUID cultivatorId = UUID.randomUUID();
        when(repository.findByCultivatorId(cultivatorId)).thenReturn(List.of());

        assertThat(service.listForCultivator(cultivatorId)).isEmpty();
    }

    @Test
    void delete_returnsTrueWhenKeyOwned() {
        UUID cultivatorId = UUID.randomUUID();
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop", KEY);
        when(repository.findByIdAndCultivatorId(key.id(), cultivatorId))
            .thenReturn(Optional.of(SshPublicKeyEntity.fromModel(key)));

        boolean deleted = service.delete(cultivatorId, key.id());

        assertThat(deleted).isTrue();
        verify(repository).deleteByIdAndCultivatorId(key.id(), cultivatorId);
    }

    @Test
    void delete_returnsFalseWhenNotOwnedOrMissing() {
        UUID cultivatorId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        when(repository.findByIdAndCultivatorId(keyId, cultivatorId)).thenReturn(Optional.empty());

        boolean deleted = service.delete(cultivatorId, keyId);

        assertThat(deleted).isFalse();
        verify(repository, never()).deleteByIdAndCultivatorId(eq(keyId), eq(cultivatorId));
    }
}
