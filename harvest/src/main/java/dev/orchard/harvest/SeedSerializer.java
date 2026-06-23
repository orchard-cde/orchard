package dev.orchard.harvest;

import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import dev.orchard.core.model.Seed;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 * Uses a {@code @type} property discriminator so polymorphic Seed subtypes round-trip correctly.
 */
public class SeedSerializer {

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .activateDefaultTypingAsProperty(
            BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Seed.class)
                .build(),
            DefaultTyping.NON_FINAL,
            "@type")
        .build();

    public String serialize(Seed seed) {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) {
        return objectMapper.readValue(json, Seed.class);
    }
}
