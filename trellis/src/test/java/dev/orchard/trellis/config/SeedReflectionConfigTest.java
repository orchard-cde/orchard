package dev.orchard.trellis.config;

import dev.orchard.harvest.DevcontainerJson;
import dev.orchard.harvest.DevfileYaml;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class SeedReflectionConfigTest {

    @Test
    void registersWorkspaceConfigRecordsAndTheirDeserializers() {
        RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, SeedReflectionConfig.class);

        for (Class<?> type : new Class<?>[] {
                DevcontainerJson.class, DevcontainerJson.Build.class, DevcontainerJson.VsCode.class,
                DevcontainerJson.HostRequirements.class, DevfileYaml.Container.class, DevfileYaml.Endpoint.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getName()).accepts(hints);
        }
        for (String deserializer : new String[] {"StringList", "StringOrList", "Mounts", "Lifecycle",
                "ObjectOnly", "Settings", "NullAsEmptyRecord"}) {
            TypeReference type = TypeReference.of("dev.orchard.harvest.LenientBinding$" + deserializer);
            assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(deserializer).accepts(hints);
        }
    }
}
