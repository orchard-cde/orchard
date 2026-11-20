package dev.orchard.roots.repository;

import dev.orchard.core.model.Secret;
import dev.orchard.roots.entity.CultivatorEntity;
import dev.orchard.roots.entity.SecretEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SecretRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SecretRepository repository;

    @Autowired
    private CultivatorRepository cultivatorRepository;

    private void seedCultivator(UUID id) {
        cultivatorRepository.save(new CultivatorEntity(
            id, "user-" + id, id + "@orchard.dev", "oidc", null, null, null,
            java.time.Instant.now(), java.time.Instant.now()));
    }

    private static String vaultKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void saveAndFindByCultivatorIdAndName() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        Secret secret = Secret.store(cultivatorId, "API_TOKEN", vaultKey());
        repository.saveAndFlush(SecretEntity.fromModel(secret));

        Secret found = repository.findByCultivatorIdAndName(cultivatorId, "API_TOKEN").orElseThrow().toModel();

        assertThat(found.id()).isEqualTo(secret.id());
        assertThat(found.scope()).isEqualTo(secret.scope());
        assertThat(found.owner()).isNull();
        assertThat(found.vaultKey()).isEqualTo(secret.vaultKey());
        assertThat(repository.findByCultivatorId(cultivatorId)).hasSize(1);
        assertThat(repository.findByCultivatorIdAndNameIn(cultivatorId, List.of("API_TOKEN", "OTHER"))).hasSize(1);
        assertThat(repository.findByCultivatorIdAndName(cultivatorId, "OTHER")).isEmpty();
    }

    @Test
    void duplicateName_sameCultivator_violatesConstraint() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        repository.saveAndFlush(SecretEntity.fromModel(Secret.store(cultivatorId, "DUP", vaultKey())));

        SecretEntity second = SecretEntity.fromModel(Secret.store(cultivatorId, "DUP", vaultKey()));

        assertThatThrownBy(() -> repository.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameName_differentCultivators_allowed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seedCultivator(a);
        seedCultivator(b);

        repository.saveAndFlush(SecretEntity.fromModel(Secret.store(a, "SHARED_NAME", vaultKey())));
        repository.saveAndFlush(SecretEntity.fromModel(Secret.store(b, "SHARED_NAME", vaultKey())));

        assertThat(repository.findByCultivatorIdAndName(a, "SHARED_NAME")).isPresent();
        assertThat(repository.findByCultivatorIdAndName(b, "SHARED_NAME")).isPresent();
    }

    @Test
    void findAllVaultKeys_returnsEveryKey() {
        UUID cultivatorId = UUID.randomUUID();
        seedCultivator(cultivatorId);
        String k1 = vaultKey();
        String k2 = vaultKey();
        repository.saveAndFlush(SecretEntity.fromModel(Secret.store(cultivatorId, "ONE", k1)));
        repository.saveAndFlush(SecretEntity.fromModel(Secret.store(cultivatorId, "TWO", k2)));

        assertThat(repository.findAllVaultKeys()).contains(k1, k2);
    }
}
