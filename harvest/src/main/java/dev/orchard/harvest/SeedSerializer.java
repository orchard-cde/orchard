package dev.orchard.harvest;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.orchard.core.model.Seed;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Serializes and deserializes Seed objects to/from JSON for storage.
 *
 * <p>Polymorphic type info (the {@code @type} property) and creator metadata live as
 * annotations on the model itself (see {@code core}'s {@code jackson-annotations}
 * dependency), so no mixins are needed here. The only mapper-level config is field
 * visibility: the model exposes record-style accessors ({@code name()} not
 * {@code getName()}), so Jackson reads private fields directly when serializing.
 *
 * <p>A {@link DeserializationProblemHandler} handles legacy seeds persisted with the
 * singular {@code "dockerComposeFile"} string key, wrapping it into a one-element list so
 * existing rows survive the {@code dockerComposeFile → dockerComposeFiles} rename.
 */
public class SeedSerializer {

    private static final ObjectMapper objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .changeDefaultVisibility(vc -> vc.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
        .addHandler(new DockerComposeFileMigrationHandler())
        .build();

    public String serialize(Seed seed) {
        return objectMapper.writeValueAsString(seed);
    }

    public Seed deserialize(String json) {
        return objectMapper.readValue(json, Seed.class);
    }

    /**
     * Handles legacy {@code seed_json} rows where {@code dockerComposeFile} was a plain
     * string. When Jackson encounters a string value for the {@code dockerComposeFiles}
     * parameter (which expects {@code List<String>}), this handler wraps it into a
     * single-element list rather than failing.
     */
    private static final class DockerComposeFileMigrationHandler extends DeserializationProblemHandler {

        @Override
        public Object handleUnexpectedToken(DeserializationContext ctx, JavaType targetType,
                JsonToken token, JsonParser p, String failMsg) throws JacksonException {
            // When deserializing List<String> for dockerComposeFiles and we encounter a
            // plain string (legacy single-file format), wrap it in a list.
            if (targetType.isCollectionLikeType()
                    && targetType.getContentType().getRawClass() == String.class
                    && token == JsonToken.VALUE_STRING) {
                java.util.ArrayList<String> list = new java.util.ArrayList<>();
                list.add(p.getText());
                return list;
            }
            return NOT_HANDLED;
        }
    }
}
