package dev.orchard.trowel;

import dev.orchard.trowel.client.OrchardClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectConfigCompletenessTest {

    private static final String RESOURCE =
        "META-INF/native-image/dev.orchard/trowel-jackson/reflect-config.json";

    @Test
    void everyOrchardClientNestedRecordIsListed() throws Exception {
        Set<String> listed = new HashSet<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            for (JsonNode entry : JsonMapper.builder().build().readTree(in)) {
                listed.add(entry.get("name").asString());
            }
        }

        String[] records = Arrays.stream(OrchardClient.class.getDeclaredClasses())
            .filter(Class::isRecord)
            .map(Class::getName)
            .toArray(String[]::new);

        assertThat(records).isNotEmpty();
        assertThat(listed).contains(records);
    }
}
