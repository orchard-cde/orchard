package dev.orchard.harvest;

import dev.orchard.core.model.LifecycleCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.deser.ValueInstantiator;
import tools.jackson.databind.jsontype.TypeDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Binding rules shared by {@link DevcontainerParser} and {@link DevfileParser}. Workspace configs are
 * hand-written, so the parsers keep the coercions they have always applied rather than Jackson's
 * defaults:
 *
 * <ul>
 *   <li>an explicit {@code null} reads as the type's empty value ({@code ""}, {@code []}, {@code {}},
 *       {@code false}, {@code 0}, or with {@link NullAsEmptyRecord} an object with every field unset),
 *       while an absent field stays {@code null};</li>
 *   <li>a list, map or object field holding the wrong JSON kind reads as empty;</li>
 *   <li>a scalar of the wrong type fails the document in {@link Mode#STRICT} and reads as unset in
 *       {@link Mode#LENIENT}.</li>
 * </ul>
 */
final class LenientBinding {

    private static final Logger log = LoggerFactory.getLogger(LenientBinding.class);

    /** STRICT for devcontainer.json, LENIENT for devfile.yaml — the coercions each parser has always had. */
    enum Mode { STRICT, LENIENT }

    private LenientBinding() {}

    static <M extends ObjectMapper, B extends MapperBuilder<M, B>> B configure(B builder, Mode mode) {
        SimpleModule scalars = new SimpleModule("orchard-config-scalars");
        scalars.addDeserializer(String.class, new NullAsBlankString());
        if (mode == Mode.STRICT) {
            scalars.addDeserializer(Boolean.class, new TruthyBoolean());
            scalars.addDeserializer(Integer.class, new TruncatingInteger());
        }
        for (LogicalType shape : List.of(LogicalType.POJO, LogicalType.Map, LogicalType.Collection)) {
            builder.withCoercionConfig(shape, c -> c
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.TryConvert)
                .setAcceptBlankAsEmpty(true));
        }
        return builder
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(scalars)
            .addHandler(new EmptyOnWrongKind(mode));
    }

    private static final class EmptyOnWrongKind extends DeserializationProblemHandler {
        private final Mode mode;

        EmptyOnWrongKind(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Object handleUnexpectedToken(DeserializationContext ctxt, JavaType targetType, JsonToken t,
                                            JsonParser p, String failureMsg) throws JacksonException {
            if (targetType.isCollectionLikeType()) {
                p.skipChildren();
                return new ArrayList<>();
            }
            if (targetType.isMapLikeType()) {
                p.skipChildren();
                return new LinkedHashMap<>();
            }
            if (targetType.isRecordType()) {
                p.skipChildren();
                return emptyRecord(ctxt, targetType);
            }
            if (mode == Mode.LENIENT && !targetType.isPrimitive()) {
                p.skipChildren();
                return null;
            }
            return NOT_HANDLED;
        }

        @Override
        public Object handleMissingInstantiator(DeserializationContext ctxt, Class<?> instClass,
                                                ValueInstantiator valueInsta, JsonParser p, String msg)
                throws JacksonException {
            JavaType type = ctxt.constructType(instClass);
            if (!type.isRecordType()) {
                return NOT_HANDLED;
            }
            p.skipChildren();
            return emptyRecord(ctxt, type);
        }

        @Override
        public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                                             String valueToConvert, String failureMsg) {
            return mode == Mode.LENIENT && !targetType.isPrimitive() ? null : NOT_HANDLED;
        }

        @Override
        public Object handleWeirdNumberValue(DeserializationContext ctxt, Class<?> targetType,
                                             Number valueToConvert, String failureMsg) {
            return mode == Mode.LENIENT && !targetType.isPrimitive() ? null : NOT_HANDLED;
        }

        static Object emptyRecord(DeserializationContext ctxt, JavaType type) throws JacksonException {
            return ctxt.readTreeAsValue(ctxt.getNodeFactory().objectNode(), type);
        }
    }

    /** Reads an explicit {@code null} as the record with every component unset, rather than as no record. */
    static final class NullAsEmptyRecord extends ValueDeserializer<Object> {
        private final JavaType type;
        private final ValueDeserializer<Object> delegate;

        NullAsEmptyRecord() {
            this(null, null);
        }

        private NullAsEmptyRecord(JavaType type, ValueDeserializer<Object> delegate) {
            this.type = type;
            this.delegate = delegate;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            JavaType target = property.getType();
            return new NullAsEmptyRecord(target, ctxt.findRootValueDeserializer(target));
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return delegate.deserialize(p, ctxt);
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) {
            return EmptyOnWrongKind.emptyRecord(ctxt, type);
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** A scalar's text; an explicit {@code null} is {@code ""}. */
    static final class NullAsBlankString extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (p.currentToken().isScalarValue()) {
                return p.getString();
            }
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }

        @Override
        public String getNullValue(DeserializationContext ctxt) {
            return "";
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** {@code true}/{@code false}, the strings {@code "true"}/{@code "false"}, or an integer (non-zero is true). */
    static final class TruthyBoolean extends ValueDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return switch (p.currentToken()) {
                case VALUE_TRUE -> true;
                case VALUE_FALSE -> false;
                case VALUE_NUMBER_INT -> p.getLongValue() != 0;
                case VALUE_STRING -> switch (p.getString()) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> ctxt.reportInputMismatch(Boolean.class, "not a boolean: '%s'", p.getString());
                };
                default -> (Boolean) ctxt.handleUnexpectedToken(Boolean.class, p);
            };
        }

        @Override
        public Boolean getNullValue(DeserializationContext ctxt) {
            return false;
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** An integer, a decimal (truncated), or a string holding an integer. */
    static final class TruncatingInteger extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return switch (p.currentToken()) {
                case VALUE_NUMBER_INT -> p.getIntValue();
                case VALUE_NUMBER_FLOAT -> (int) p.getDoubleValue();
                case VALUE_STRING -> {
                    try {
                        yield Integer.parseInt(p.getString());
                    } catch (NumberFormatException e) {
                        yield ctxt.reportInputMismatch(Integer.class, "not an integer: '%s'", p.getString());
                    }
                }
                default -> (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
            };
        }

        @Override
        public Integer getNullValue(DeserializationContext ctxt) {
            return 0;
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** An array of strings; an object contributes its values; any other shape is an empty list. */
    static final class StringList extends ValueDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            List<String> items = new ArrayList<>();
            forEachElement(p, () -> items.add(text(p, ctxt)));
            return items;
        }

        @Override
        public List<String> getNullValue(DeserializationContext ctxt) {
            return new ArrayList<>();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** {@code "a"} or {@code ["a", "b"]}; any other shape is an empty list. */
    static final class StringOrList extends ValueDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return stringOrList(p, ctxt);
        }

        @Override
        public List<String> getNullValue(DeserializationContext ctxt) {
            return new ArrayList<>();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /**
     * Mounts: each entry is a {@code docker --mount} string, or an object rendered to one as
     * {@code key=value,...}; any other entry renders as {@code ""}.
     */
    static final class Mounts extends ValueDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            List<String> mounts = new ArrayList<>();
            forEachElement(p, () -> {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    mounts.add(p.getString());
                    return;
                }
                StringJoiner rendered = new StringJoiner(",");
                if (p.currentToken() == JsonToken.START_OBJECT) {
                    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                        String key = p.currentName();
                        p.nextToken();
                        rendered.add(key + "=" + text(p, ctxt));
                    }
                } else {
                    p.skipChildren();
                }
                mounts.add(rendered.toString());
            });
            return mounts;
        }

        @Override
        public List<String> getNullValue(DeserializationContext ctxt) {
            return new ArrayList<>();
        }

        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return null;
        }
    }

    /** An object of arbitrary values (feature options, editor settings); any other shape fails the document. */
    static class ObjectOnly extends ValueDeserializer<Map<String, Object>> {
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (p.currentToken() != JsonToken.START_OBJECT) {
                return ctxt.reportInputMismatch(Map.class, "expected an object, got %s", p.currentToken());
            }
            return ctxt.readValue(p, LinkedHashMap.class);
        }
    }

    /** Editor settings: an absent field is an empty object, an explicit {@code null} stays {@code null}. */
    static final class Settings extends ObjectOnly {
        @Override
        public Object getAbsentValue(DeserializationContext ctxt) {
            return new LinkedHashMap<String, Object>();
        }
    }

    /**
     * A lifecycle command: a shell string or an argv array (one {@link LifecycleCommand.Sequential}),
     * or an object of named steps, each a string or argv array, run in parallel.
     */
    static final class Lifecycle extends ValueDeserializer<LifecycleCommand> {
        @Override
        public LifecycleCommand deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            return switch (p.currentToken()) {
                case VALUE_STRING, START_ARRAY -> new LifecycleCommand.Sequential(stringOrList(p, ctxt));
                case START_OBJECT -> {
                    Map<String, List<String>> steps = new LinkedHashMap<>();
                    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                        String step = p.currentName();
                        p.nextToken();
                        steps.put(step, stringOrList(p, ctxt));
                    }
                    yield new LifecycleCommand.Parallel(steps);
                }
                default -> {
                    log.warn("Unrecognised lifecycle command node type, ignoring");
                    yield null;
                }
            };
        }

        /** The spec shape carries no {@code @type} id; that discriminator is only for persisted seeds. */
        @Override
        public Object deserializeWithType(JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
                throws JacksonException {
            return deserialize(p, ctxt);
        }
    }

    @FunctionalInterface
    private interface ElementReader {
        void read() throws JacksonException;
    }

    /** Visits each array element or object value with the parser positioned on it. */
    private static void forEachElement(JsonParser p, ElementReader reader) throws JacksonException {
        switch (p.currentToken()) {
            case START_ARRAY -> {
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    reader.read();
                }
            }
            case START_OBJECT -> {
                while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                    p.nextToken();
                    reader.read();
                }
            }
            default -> p.skipChildren();
        }
    }

    private static List<String> stringOrList(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        return switch (p.currentToken()) {
            case VALUE_STRING -> new ArrayList<>(List.of(p.getString()));
            case START_ARRAY -> {
                List<String> items = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    items.add(text(p, ctxt));
                }
                yield items;
            }
            default -> {
                p.skipChildren();
                yield new ArrayList<>();
            }
        };
    }

    /** A scalar's text ({@code ""} for null); an object or array fails the document. */
    private static String text(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken token = p.currentToken();
        if (token.isStructStart()) {
            return ctxt.reportInputMismatch(String.class, "expected a string, got %s", token);
        }
        return token == JsonToken.VALUE_NULL ? "" : p.getString();
    }
}
