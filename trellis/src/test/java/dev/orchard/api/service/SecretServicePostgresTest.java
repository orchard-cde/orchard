package dev.orchard.api.service;

import dev.orchard.knothole.SecretVault;
import dev.orchard.roots.entity.SecretEntity;
import dev.orchard.roots.repository.SecretRepository;
import dev.orchard.trellis.config.KnotholeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@DisabledInAotMode
@SpringBootTest(
    classes = SecretServicePostgresTest.Config.class,
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
    })
class SecretServicePostgresTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("dev.orchard.roots.entity")
    @EnableJpaRepositories("dev.orchard.roots.repository")
    @Import({SecretService.class, KnotholeConfig.class, CultivatorService.class})
    static class Config {}

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("orchard.knothole.vault-path", () -> tempDir.resolve("vault").toString());
        registry.add("orchard.knothole.master-key-path", () -> tempDir.resolve("master.key").toString());
    }

    @Autowired private SecretService service;
    @Autowired private SecretVault vault;
    @MockitoSpyBean private SecretRepository repository;

    @Test
    void put_concurrentFirstWrite_hitsConstraintInsideCallback_thenOverwrites() {
        UUID cultivatorId = UUID.randomUUID();
        String name = "RACED_TOKEN";
        service.put(cultivatorId, name, "first".getBytes(StandardCharsets.UTF_8));

        AtomicBoolean hidden = new AtomicBoolean();
        doAnswer(inv -> hidden.compareAndSet(false, true) ? Optional.empty() : repository.findByCultivatorId(cultivatorId).stream().filter(e -> name.equals(e.getName())).findFirst())
            .when(repository).findByCultivatorIdAndName(eq(cultivatorId), eq(name));

        SecretService.Stored stored = service.put(cultivatorId, name, "second".getBytes(StandardCharsets.UTF_8));

        assertThat(stored.created()).isFalse();
        assertThat(repository.findByCultivatorId(cultivatorId)).hasSize(1)
            .extracting(SecretEntity::getVaultKey).containsExactly(stored.secret().vaultKey());
        assertThat(vault.keys()).containsExactly(stored.secret().vaultKey());
        assertThat(service.resolve(cultivatorId, java.util.List.of(name)).get(name))
            .isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }
}
