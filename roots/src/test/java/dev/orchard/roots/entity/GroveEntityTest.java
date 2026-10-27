package dev.orchard.roots.entity;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroveEntityTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void fromModel_roundTripsVmPlot() {
        Grove grove = grove();
        VmPlot plot = vmPlot(grove.id()).withAuthorizedKeys(List.of("ssh-ed25519 AAAA test"));

        Plot reloaded = GroveEntity.fromModel(grove.withPlot(plot)).plot();

        assertThat(reloaded).isEqualTo(plot.withAuthorizedKeys(List.of()));
    }

    @Test
    void fromModel_roundTripsContainerPlot() {
        Grove grove = grove();
        ContainerPlot plot = new ContainerPlot(UUID.randomUUID(), grove.id(), "docker", "c-123",
            PlotState.SAPLING, NOW, NOW, Path.of("/srv/groves/g1"), ContainerRuntime.PODMAN,
            new ContainerSpec(2, 4096));

        Plot reloaded = GroveEntity.fromModel(grove.withPlot(plot)).plot();

        assertThat(reloaded).isInstanceOf(ContainerPlot.class).isEqualTo(plot);
    }

    @Test
    void fromModel_nullPlot_writesNullJson() throws Exception {
        GroveEntity entity = GroveEntity.fromModel(grove());

        assertThat(plotJson(entity)).isNull();
    }

    @Test
    void plot_blankJson_throwsWithDevServerHint() {
        GroveEntity entity = GroveEntity.fromModel(grove());

        assertThatThrownBy(entity::plot)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("~/.orchard/data");
    }

    @Test
    void setPlot_overwritesJson() throws Exception {
        Grove grove = grove();
        VmPlot original = vmPlot(grove.id());
        GroveEntity entity = GroveEntity.fromModel(grove.withPlot(original));
        String before = plotJson(entity);

        VmPlot withered = original.withState(PlotState.WITHERED);
        entity.setPlot(withered);

        assertThat(plotJson(entity)).isNotEqualTo(before);
        assertThat(entity.plot()).isEqualTo(withered);
    }

    private static Grove grove() {
        return Grove.plant(UUID.randomUUID(), "g", "https://github.com/test/repo", "main");
    }

    private static VmPlot vmPlot(UUID groveId) {
        return new VmPlot(UUID.randomUUID(), groveId, "qemu", "vm-1", PlotState.SAPLING, NOW, NOW,
            "10.0.0.5", 2222, List.of(), VmSpec.medium());
    }

    private static String plotJson(GroveEntity entity) throws Exception {
        Field field = GroveEntity.class.getDeclaredField("plotJson");
        field.setAccessible(true);
        return (String) field.get(entity);
    }
}
