package dev.orchard.trowel.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void load_noFile_returnsNull() {
        assertThat(ConfigLoader.load()).isNull();
    }

    @Test
    void load_tomlFile_returnsConfig() throws IOException {
        String toml = """
                active = "local"

                [targets.local]
                server = "http://localhost:7778"
                cultivator = "test-uuid"
                """;
        Files.createDirectories(ConfigLoader.configDir());
        Files.writeString(ConfigLoader.tomlFile(), toml);

        OrchardConfig config = ConfigLoader.load();

        assertThat(config).isNotNull();
        assertThat(config.active()).isEqualTo("local");
        assertThat(config.targets().get("local").server()).isEqualTo("http://localhost:7778");
        assertThat(config.targets().get("local").cultivator()).isEqualTo("test-uuid");
    }

    @Test
    void load_legacyPropertiesFile_returnsSynthesizedDefaultTarget() throws IOException {
        Files.createDirectories(ConfigLoader.configDir());
        Files.writeString(ConfigLoader.legacyFile(),
                "server=http://legacy:7778\ncultivator=legacy-uuid\n");

        OrchardConfig config = ConfigLoader.load();

        assertThat(config).isNotNull();
        assertThat(config.active()).isEqualTo("default");
        assertThat(config.targets().get("default").server()).isEqualTo("http://legacy:7778");
        assertThat(config.targets().get("default").cultivator()).isEqualTo("legacy-uuid");
    }

    @Test
    void load_tomlTakesPrecedenceOverProperties() throws IOException {
        String toml = """
                active = "local"

                [targets.local]
                server = "http://toml-server:7778"
                cultivator = "toml-uuid"
                """;
        Files.createDirectories(ConfigLoader.configDir());
        Files.writeString(ConfigLoader.tomlFile(), toml);
        Files.writeString(ConfigLoader.legacyFile(), "server=http://props-server:7778\n");

        OrchardConfig config = ConfigLoader.load();

        assertThat(config.targets().get("local").server()).isEqualTo("http://toml-server:7778");
    }

    @Test
    void save_writesReadableToml() throws IOException {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("local", new OrchardConfig.Target("http://localhost:7778", "my-uuid"));
        var config = new OrchardConfig("local", targets);

        ConfigLoader.save(config);

        assertThat(ConfigLoader.tomlFile()).exists();
        OrchardConfig reloaded = ConfigLoader.load();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.active()).isEqualTo("local");
        assertThat(reloaded.targets().get("local").server()).isEqualTo("http://localhost:7778");
        assertThat(reloaded.targets().get("local").cultivator()).isEqualTo("my-uuid");
    }

    @Test
    void save_targetWithNullField_omitsItAndRoundTripsToNull() throws IOException {
        // A legacy config.properties missing a 'cultivator=' line synthesizes a
        // Target with a null cultivator. TOML cannot represent null, so without
        // NON_NULL inclusion this serializes to cultivator = "" and reads back as
        // an empty string, silently corrupting the value. It must stay null.
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("default", new OrchardConfig.Target("http://legacy:7778", null));
        ConfigLoader.save(new OrchardConfig("default", targets));

        OrchardConfig reloaded = ConfigLoader.load();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.targets().get("default").server()).isEqualTo("http://legacy:7778");
        assertThat(reloaded.targets().get("default").cultivator()).isNull();
    }

    @Test
    void withDefault_producesValidSingleTargetConfig() {
        OrchardConfig config = OrchardConfig.withDefault();

        assertThat(config.active()).isEqualTo("local");
        assertThat(config.targets()).containsKey("local");
        assertThat(config.targets().get("local").server()).isEqualTo("http://localhost:7778");
        assertThat(config.targets().get("local").cultivator()).isNotBlank();
    }

    @Test
    void activeTarget_returnsTargetForActiveName() {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("staging", new OrchardConfig.Target("https://staging.example.com", "s-uuid"));
        var config = new OrchardConfig("staging", targets);

        assertThat(config.activeTarget().server()).isEqualTo("https://staging.example.com");
    }

    @Test
    void activeTarget_unknownActiveName_returnsNull() {
        var config = new OrchardConfig("missing", new LinkedHashMap<>());

        assertThat(config.activeTarget()).isNull();
    }

    @Test
    void target_twoArgConstructor_defaultsNewFieldsToNull() {
        var target = new OrchardConfig.Target("http://localhost:7778", "some-uuid");

        assertThat(target.fenceServer()).isNull();
        assertThat(target.accessToken()).isNull();
        assertThat(target.refreshToken()).isNull();
        assertThat(target.expiresAt()).isNull();
    }

    @Test
    void save_and_load_roundTripsTokenFields() throws IOException {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("local", new OrchardConfig.Target(
            "http://localhost:7778",
            "3f2a1c4e-0000-0000-0000-0000000009b7",
            "http://localhost:7779",
            "access-token-abc",
            "refresh-token-xyz",
            1785036296L
        ));
        ConfigLoader.save(new OrchardConfig("local", targets));

        OrchardConfig reloaded = ConfigLoader.load();

        assertThat(reloaded).isNotNull();
        OrchardConfig.Target target = reloaded.targets().get("local");
        assertThat(target.fenceServer()).isEqualTo("http://localhost:7779");
        assertThat(target.accessToken()).isEqualTo("access-token-abc");
        assertThat(target.refreshToken()).isEqualTo("refresh-token-xyz");
        assertThat(target.expiresAt()).isEqualTo(1785036296L);
    }

    @Test
    void save_targetWithNullTokenFields_omitsThemAndRoundTripsToNull() throws IOException {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("local", new OrchardConfig.Target("http://localhost:7778", "some-uuid"));
        ConfigLoader.save(new OrchardConfig("local", targets));

        OrchardConfig reloaded = ConfigLoader.load();

        OrchardConfig.Target target = reloaded.targets().get("local");
        assertThat(target.fenceServer()).isNull();
        assertThat(target.accessToken()).isNull();
        assertThat(target.refreshToken()).isNull();
        assertThat(target.expiresAt()).isNull();
    }

    @Test
    void save_writesSnakeCaseKeysForTokenFields() throws Exception {
        var targets = new LinkedHashMap<String, OrchardConfig.Target>();
        targets.put("local", new OrchardConfig.Target(
            "http://localhost:7778",
            "3f2a1c4e-0000-0000-0000-0000000009b7",
            "http://localhost:7779",
            "access-token-abc",
            "refresh-token-xyz",
            1785036296L
        ));
        ConfigLoader.save(new OrchardConfig("local", targets));

        String written = Files.readString(ConfigLoader.tomlFile());

        assertThat(written).contains("fence_server");
        assertThat(written).contains("access_token");
        assertThat(written).contains("refresh_token");
        assertThat(written).contains("expires_at");
        assertThat(written).doesNotContain("fenceServer");
        assertThat(written).doesNotContain("accessToken");
    }
}
