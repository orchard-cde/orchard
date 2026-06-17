package dev.orchard.trellis.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

class SpaResourceConfigTest {

    @TempDir
    static Path staticDir;

    static FileSystemResource location;

    @BeforeAll
    static void setup() throws IOException {
        Files.writeString(staticDir.resolve("index.html"), "<!doctype html><title>shell</title>");
        Files.writeString(staticDir.resolve("favicon.ico"), "icon-bytes");
        // trailing slash so createRelative resolves children correctly
        location = new FileSystemResource(staticDir.toFile().getPath() + "/");
    }

    private final SpaResourceConfig.SpaPathResourceResolver resolver =
        new SpaResourceConfig.SpaPathResourceResolver();

    @Test
    void clientRouteReturnsSpaShell() throws IOException {
        Resource result = resolver.getResource("groves/abc-123", location);
        assertThat(result).isNotNull();
        assertThat(result.getFilename()).isEqualTo("index.html");
    }

    @Test
    void rootReturnsSpaShell() throws IOException {
        Resource result = resolver.getResource("", location);
        assertThat(result).isNotNull();
        assertThat(result.getFilename()).isEqualTo("index.html");
    }

    @Test
    void existingAssetReturnedDirectly() throws IOException {
        Resource result = resolver.getResource("favicon.ico", location);
        assertThat(result).isNotNull();
        assertThat(result.getFilename()).isEqualTo("favicon.ico");
    }

    @Test
    void missingDottedAssetReturnsNull() throws IOException {
        Resource result = resolver.getResource("_next/static/chunks/main.js", location);
        assertThat(result).isNull();
    }

    @Test
    void apiPrefixReturnsNull() throws IOException {
        Resource result = resolver.getResource("api/anything", location);
        assertThat(result).isNull();
    }

    @Test
    void actuatorPrefixReturnsNull() throws IOException {
        Resource result = resolver.getResource("actuator/health", location);
        assertThat(result).isNull();
    }

    @Test
    void wsPrefixReturnsNull() throws IOException {
        assertThat(resolver.getResource("ws/grove-events", location)).isNull();
    }
}
