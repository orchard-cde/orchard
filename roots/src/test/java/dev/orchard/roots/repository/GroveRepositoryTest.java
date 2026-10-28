package dev.orchard.roots.repository;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.PlotState;
import dev.orchard.roots.entity.CultivatorEntity;
import dev.orchard.roots.entity.GroveEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Transactional
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GroveRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private GroveRepository repository;

    @Autowired
    private CultivatorRepository cultivatorRepository;

    private Grove seedGrove(GroveState state, PlotState plotState) {
        UUID cultivatorId = UUID.randomUUID();
        cultivatorRepository.save(new CultivatorEntity(
            cultivatorId, "user-" + cultivatorId, cultivatorId + "@orchard.dev", "oidc", null, null, null,
            Instant.now(), Instant.now()));
        Grove planted = Grove.plant(cultivatorId, "g", "https://example.com/r.git", "main");
        Grove grove = planted.withState(state).withPlot(plot(planted.id(), plotState));
        repository.saveAndFlush(GroveEntity.fromModel(grove));
        return grove;
    }

    private ContainerPlot plot(UUID groveId, PlotState state) {
        Instant now = Instant.now();
        return new ContainerPlot(UUID.randomUUID(), groveId, "docker", "c-123", state, now, now,
            Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN, new ContainerSpec(2, 4096));
    }

    @Test
    void transitionIfState_matchingState_updatesAndReturns1() {
        Grove grove = seedGrove(GroveState.FLOURISHING, PlotState.ROOTED);
        ContainerPlot next = plot(grove.id(), PlotState.DORMANT);

        int updated = repository.transitionIfState(grove.id(), GroveState.FLOURISHING, GroveState.DORMANT,
            GroveEntity.serializePlot(next));

        GroveEntity reloaded = repository.findById(grove.id()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(reloaded.getState()).isEqualTo(GroveState.DORMANT);
        assertThat(reloaded.plot()).isEqualTo(next);
    }

    @Test
    void transitionIfState_mismatch_returns0_rowUnchanged() {
        Grove grove = seedGrove(GroveState.CLEARING, PlotState.ROOTED);
        ContainerPlot next = plot(grove.id(), PlotState.DORMANT);

        int updated = repository.transitionIfState(grove.id(), GroveState.FLOURISHING, GroveState.DORMANT,
            GroveEntity.serializePlot(next));

        GroveEntity reloaded = repository.findById(grove.id()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(reloaded.getState()).isEqualTo(GroveState.CLEARING);
        assertThat(reloaded.plot()).isEqualTo(grove.plot());
    }
}
