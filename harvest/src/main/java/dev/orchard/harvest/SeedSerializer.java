package dev.orchard.harvest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import dev.orchard.core.model.Seed;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 *
 * <p>Polymorphic type info (the {@code @type} property) and creator metadata live as
 * annotations on the model itself (see {@code core}'s {@code jackson-annotations}
 * dependency), so no mixins are needed here. The only mapper-level config is field
 * visibility: the model exposes record-style accessors ({@code name()} not
 * {@code getName()}), so Jackson reads private fields directly when serializing.
 */
public class SeedSerializer {

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .changeDefaultVisibility(vc -> vc.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
        .build();

    public String serialize(Seed seed) {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) {
        return objectMapper.readValue(json, Seed.class);
    }
}
