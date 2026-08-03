package dev.orchard.roots.repository;

import dev.orchard.core.model.SshPublicKey;
import dev.orchard.roots.entity.CultivatorEntity;
import dev.orchard.roots.entity.SshPublicKeyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SshPublicKeyRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SshPublicKeyRepository repository;

    @Autowired
    private CultivatorRepository cultivatorRepository;

    private void seedCultivator(UUID id) {
        cultivatorRepository.save(new CultivatorEntity(
            id, "user-" + id, id + "@orchard.dev", "oidc", null, null, null,
            java.time.Instant.now(), java.time.Instant.now()));
    }

    @Test
    void saveAndFindByCultivatorId() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");
        repository.save(SshPublicKeyEntity.fromModel(key));

        List<SshPublicKeyEntity> found = repository.findByCultivatorId(cultivatorId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).toModel().fingerprint()).isEqualTo(key.fingerprint());
    }

    @Test
    void findByCultivatorId_scopesToCultivator() {
        UUID cultivatorA = UUID.randomUUID();
        UUID cultivatorB = UUID.randomUUID();
        seedCultivator(cultivatorA);
        seedCultivator(cultivatorB);
        repository.save(SshPublicKeyEntity.fromModel(
            SshPublicKey.register(cultivatorA, "a", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR a@example.com")));
        repository.save(SshPublicKeyEntity.fromModel(
            SshPublicKey.register(cultivatorB, "b", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR b@example.com")));

        assertThat(repository.findByCultivatorId(cultivatorA)).hasSize(1);
        assertThat(repository.findByCultivatorId(cultivatorB)).hasSize(1);
        assertThat(repository.findByCultivatorId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByIdAndCultivatorId_returnsKeyForOwner() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");
        repository.save(SshPublicKeyEntity.fromModel(key));

        Optional<SshPublicKeyEntity> owned = repository.findByIdAndCultivatorId(key.id(), cultivatorId);
        Optional<SshPublicKeyEntity> other = repository.findByIdAndCultivatorId(key.id(), UUID.randomUUID());

        assertThat(owned).isPresent();
        assertThat(other).isEmpty();
    }

    @Test
    void deleteByIdScopedToCultivator() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");
        repository.save(SshPublicKeyEntity.fromModel(key));

        repository.deleteByIdAndCultivatorId(key.id(), cultivatorId);

        assertThat(repository.findById(key.id())).isEmpty();
    }

    @Test
    void roundTrip_preservesModelFields() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        SshPublicKey key = SshPublicKey.register(cultivatorId, "work-laptop",
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINu3uCEBEcYqqjErEWRewbGZw8qrfWt/0inp+HfZR7MR test@orchard.dev");

        repository.save(SshPublicKeyEntity.fromModel(key));
        SshPublicKey restored = repository.findById(key.id()).orElseThrow().toModel();

        assertThat(restored.id()).isEqualTo(key.id());
        assertThat(restored.cultivatorId()).isEqualTo(key.cultivatorId());
        assertThat(restored.name()).isEqualTo(key.name());
        assertThat(restored.publicKey()).isEqualTo(key.publicKey());
        assertThat(restored.fingerprint()).isEqualTo(key.fingerprint());
        assertThat(restored.createdAt()).isAfterOrEqualTo(key.createdAt().minusSeconds(1))
            .isBeforeOrEqualTo(key.createdAt().plusSeconds(1));
    }
}
