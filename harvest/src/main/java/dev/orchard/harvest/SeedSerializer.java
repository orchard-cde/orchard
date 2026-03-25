package dev.orchard.harvest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import dev.orchard.core.model.Seed;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 */
public class SeedSerializer {

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    public String serialize(Seed seed) {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) {
        return objectMapper.readValue(json, Seed.class);
    }
}
