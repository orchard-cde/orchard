package dev.orchard.harvest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;
import tools.jackson.databind.json.JsonMapper;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 *
 * <p>Polymorphic type info and Jackson binding config are applied via mixins so that
 * {@code core} stays annotation-free. The {@code @type} property is written on
 * serialize and consumed on deserialize. {@code defaultImpl = DevcontainerSeed.class}
 * provides backward compatibility with pre-refactor rows that have no {@code @type}.
 */
public class SeedSerializer {

    /** Adds @type discriminator scoped only to the Seed hierarchy. */
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@type",
        defaultImpl = DevcontainerSeed.class)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DevcontainerSeed.class, name = "devcontainer")
    })
    // Read fields (not getters) since Seed uses non-standard accessor names (name() not getName())
    @JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
    abstract static class SeedMixin {}

    /** Routes deserialization through DevcontainerSeed.Builder. */
    @JsonDeserialize(builder = DevcontainerSeed.Builder.class)
    @JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
    abstract static class DevcontainerSeedMixin {}

    /** Tells Jackson the builder setters have no prefix (name(x) not withName(x)). */
    @JsonPOJOBuilder(withPrefix = "")
    abstract static class DevcontainerSeedBuilderMixin {}

    /**
     * Adds a @type discriminator to the sealed LifecycleCommand hierarchy. Without this,
     * a DevcontainerSeed carrying any lifecycle command (e.g. postCreateCommand) serializes
     * the record's fields with no discriminator and then fails to deserialize, because the
     * field's declared type is the abstract LifecycleCommand interface.
     *
     * <p>No {@code defaultImpl} is set: a discriminator-less lifecycle command is ambiguous
     * between Sequential and Parallel (the two shapes differ), so such rows are rejected
     * rather than silently coerced to the wrong subtype.
     */
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = LifecycleCommand.Sequential.class, name = "sequential"),
        @JsonSubTypes.Type(value = LifecycleCommand.Parallel.class, name = "parallel")
    })
    abstract static class LifecycleCommandMixin {}

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .addMixIn(Seed.class, SeedMixin.class)
        .addMixIn(DevcontainerSeed.class, DevcontainerSeedMixin.class)
        .addMixIn(DevcontainerSeed.Builder.class, DevcontainerSeedBuilderMixin.class)
        .addMixIn(LifecycleCommand.class, LifecycleCommandMixin.class)
        .build();

    public String serialize(Seed seed) {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) {
        return objectMapper.readValue(json, Seed.class);
    }
}
