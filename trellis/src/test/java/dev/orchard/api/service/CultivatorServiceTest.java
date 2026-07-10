package dev.orchard.api.service;

import dev.orchard.core.model.Cultivator;
import dev.orchard.roots.entity.CultivatorEntity;
import dev.orchard.roots.repository.CultivatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CultivatorServiceTest {

    @Mock
    private CultivatorRepository cultivatorRepository;

    private CultivatorService cultivatorService;

    @BeforeEach
    void setUp() {
        cultivatorService = new CultivatorService(cultivatorRepository);
    }

    @Test
    void findOrCreateCultivator_returnsExistingAndUpdatesLastActiveAt() {
        CultivatorEntity existing = new CultivatorEntity(
            UUID.randomUUID(), "alice", "alice@example.com", "github", "gh-123",
            "https://avatar.url", "Alice", Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600)
        );
        when(cultivatorRepository.findByProviderAndProviderId("github", "gh-123"))
            .thenReturn(Optional.of(existing));

        Cultivator result = cultivatorService.findOrCreateCultivator(
            "github", "gh-123", "alice", "alice@example.com", "https://avatar.url", "Alice");

        assertThat(result.id()).isEqualTo(existing.getId());
        verify(cultivatorRepository).save(existing);
    }

    @Test
    void findOrCreateCultivator_updatesEmailWhenChanged() {
        CultivatorEntity existing = new CultivatorEntity(
            UUID.randomUUID(), "alice", "old@example.com", "github", "gh-123",
            null, null, Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findByProviderAndProviderId("github", "gh-123"))
            .thenReturn(Optional.of(existing));

        cultivatorService.findOrCreateCultivator(
            "github", "gh-123", "alice", "new@example.com", null, null);

        assertThat(existing.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void findOrCreateCultivator_updatesAvatarUrlWhenChanged() {
        CultivatorEntity existing = new CultivatorEntity(
            UUID.randomUUID(), "alice", "alice@example.com", "github", "gh-123",
            "old-avatar", null, Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findByProviderAndProviderId("github", "gh-123"))
            .thenReturn(Optional.of(existing));

        cultivatorService.findOrCreateCultivator(
            "github", "gh-123", "alice", "alice@example.com", "new-avatar", null);

        assertThat(existing.getAvatarUrl()).isEqualTo("new-avatar");
    }

    @Test
    void findOrCreateCultivator_updatesDisplayNameWhenChanged() {
        CultivatorEntity existing = new CultivatorEntity(
            UUID.randomUUID(), "alice", "alice@example.com", "github", "gh-123",
            null, "Old Name", Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findByProviderAndProviderId("github", "gh-123"))
            .thenReturn(Optional.of(existing));

        cultivatorService.findOrCreateCultivator(
            "github", "gh-123", "alice", "alice@example.com", null, "New Name");

        assertThat(existing.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void findOrCreateCultivator_createsNewWhenNotFound() {
        when(cultivatorRepository.findByProviderAndProviderId("github", "gh-999"))
            .thenReturn(Optional.empty());

        Cultivator result = cultivatorService.findOrCreateCultivator(
            "github", "gh-999", "bob", "bob@example.com", "https://avatar", "Bob");

        assertThat(result.username()).isEqualTo("bob");
        assertThat(result.email()).isEqualTo("bob@example.com");
        assertThat(result.provider()).isEqualTo("github");
        assertThat(result.providerId()).isEqualTo("gh-999");

        ArgumentCaptor<CultivatorEntity> captor = ArgumentCaptor.forClass(CultivatorEntity.class);
        verify(cultivatorRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("bob");
    }

    @Test
    void ensureCultivator_returnsExistingAndUpdatesLastActive() {
        UUID cultivatorId = UUID.randomUUID();
        CultivatorEntity existing = new CultivatorEntity(
            cultivatorId, "alice", "alice@example.com", "oidc", null,
            null, null, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600)
        );
        when(cultivatorRepository.findById(cultivatorId)).thenReturn(Optional.of(existing));

        Cultivator result = cultivatorService.ensureCultivator(cultivatorId);

        assertThat(result.id()).isEqualTo(cultivatorId);
        verify(cultivatorRepository).save(existing);
    }

    @Test
    void ensureCultivator_createsLocalDevCultivatorWhenNotFound() {
        UUID cultivatorId = UUID.randomUUID();
        when(cultivatorRepository.findById(cultivatorId)).thenReturn(Optional.empty());

        Cultivator result = cultivatorService.ensureCultivator(cultivatorId);

        assertThat(result.id()).isEqualTo(cultivatorId);
        assertThat(result.username()).startsWith("cultivator-");
        assertThat(result.email()).contains("@local");
        assertThat(result.provider()).isEqualTo("local");

        verify(cultivatorRepository).save(any(CultivatorEntity.class));
    }

    @Test
    void findById_returnsCultivator() {
        UUID id = UUID.randomUUID();
        CultivatorEntity entity = new CultivatorEntity(
            id, "alice", "alice@example.com", "oidc", null,
            null, null, Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<Cultivator> result = cultivatorService.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    @Test
    void findById_returnsEmpty() {
        when(cultivatorRepository.findById(any())).thenReturn(Optional.empty());

        assertThat(cultivatorService.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByUsername_returnsCultivator() {
        CultivatorEntity entity = new CultivatorEntity(
            UUID.randomUUID(), "alice", "alice@example.com", "oidc", null,
            null, null, Instant.now(), Instant.now()
        );
        when(cultivatorRepository.findByUsername("alice")).thenReturn(Optional.of(entity));

        Optional<Cultivator> result = cultivatorService.findByUsername("alice");

        assertThat(result).isPresent();
        assertThat(result.get().username()).isEqualTo("alice");
    }

    @Test
    void findByUsername_returnsEmpty() {
        when(cultivatorRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThat(cultivatorService.findByUsername("unknown")).isEmpty();
    }
}
