package dev.orchard.trowel;

import dev.orchard.trowel.client.OrchardClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectConfigCompletenessTest {

    private static final String RESOURCE =
        "META-INF/native-image/dev.orchard/trowel-jackson/reflect-config.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReflectEntry(String name) {}

    @Test
    void everyOrchardClientNestedRecordIsListed() throws Exception {
        Set<String> listed;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            listed = Arrays.stream(JsonMapper.builder().build().readValue(in, ReflectEntry[].class))
                .map(ReflectEntry::name)
                .collect(Collectors.toSet());
        }

        String[] records = Arrays.stream(OrchardClient.class.getDeclaredClasses())
            .filter(Class::isRecord)
            .map(Class::getName)
            .toArray(String[]::new);

        assertThat(records).isNotEmpty();
        assertThat(listed).contains(records);
    }
}
