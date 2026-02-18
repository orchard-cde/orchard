package dev.orchard.harvest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.orchard.core.model.Seed;

import java.io.IOException;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 */
public class SeedSerializer {

    private static final ObjectMapper objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    public String serialize(Seed seed) throws JsonProcessingException {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) throws IOException {
        return objectMapper.readValue(json, Seed.class);
    }
}
