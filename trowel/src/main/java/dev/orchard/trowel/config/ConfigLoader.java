package dev.orchard.trowel.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;

public class ConfigLoader {

    // TOML has no null literal: jackson-dataformat-toml does not fail on a null
    // property, it writes an empty string, which then reads back as "" (not null)
    // and silently corrupts a target whose server/cultivator was never set (e.g.
    // a legacy config.properties missing a key). Omitting null properties keeps a
    // missing value absent, so it round-trips back to null.
    private static final TomlMapper MAPPER = TomlMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    public static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".orchard");
    }

    public static Path tomlFile() {
        return configDir().resolve("config.toml");
    }

    public static Path legacyFile() {
        return configDir().resolve("config.properties");
    }

    public static OrchardConfig load() {
        if (Files.exists(tomlFile())) {
            try {
                return MAPPER.readValue(tomlFile(), OrchardConfig.class);
            } catch (JacksonException e) {
                System.err.println("Warning: failed to read config.toml: " + e.getMessage());
                return null;
            }
        }
        if (Files.exists(legacyFile())) {
            try {
                Properties props = new Properties();
                props.load(Files.newBufferedReader(legacyFile()));
                var targets = new LinkedHashMap<String, OrchardConfig.Target>();
                targets.put("default", new OrchardConfig.Target(
                        props.getProperty("server"),
                        props.getProperty("cultivator")));
                return new OrchardConfig("default", targets);
            } catch (IOException e) {
                System.err.println("Warning: failed to read config.properties: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    public static void save(OrchardConfig config) throws IOException {
        Files.createDirectories(configDir());
        MAPPER.writeValue(tomlFile(), config);
    }
}
