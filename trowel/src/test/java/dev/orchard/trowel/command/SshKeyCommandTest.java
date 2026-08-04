package dev.orchard.trowel.command;

import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.ssh.SshKeyPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SshKeyCommandTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private final PrintStream originalOut = System.out;
    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/ssh-keys", exchange -> {
            hits.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] resp;
            int status;
            switch (exchange.getRequestMethod()) {
                case "POST" -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    hits.add(body);
                    resp = ("{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"default\","
                            + "\"publicKey\":\"ssh-ed25519 AAAA\",\"fingerprint\":\"SHA256:test\","
                            + "\"createdAt\":\"2026-08-03T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
                    status = 201;
                }
                case "GET" -> {
                    resp = "[]".getBytes(StandardCharsets.UTF_8);
                    status = 200;
                }
                default -> {
                    resp = new byte[0];
                    status = 204;
                }
            }
            exchange.sendResponseHeaders(status, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                exchange.getResponseBody().write(resp);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.setProperty("user.home", originalHome);
        System.setOut(originalOut);
    }

    private String run(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int exit = new CommandLine(new Trowel()).execute(args);
        System.setOut(originalOut);
        assertThat(exit).isZero();
        return buf.toString(StandardCharsets.UTF_8);
    }

    private String serverUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void add_generatesLocalKeyAndRegistersPublicHalf() {
        String out = run("--server", serverUrl(), "ssh-key", "add");

        assertThat(out).contains("Registered SSH key");
        assertThat(SshKeyPaths.privateKey("default")).exists();
        assertThat(SshKeyPaths.publicKey("default")).exists();
        assertThat(hits).anyMatch(h -> h.startsWith("POST /api/ssh-keys"));
        assertThat(hits).anyMatch(h -> h.contains("\"publicKey\":\"ssh-ed25519 "));
    }

    @Test
    void add_withPathOption_registersExistingKeyWithoutGenerating() throws Exception {
        Path existingKey = tempDir.resolve("imported.pub");
        Files.writeString(existingKey, "ssh-ed25519 AAAAimported imported@elsewhere\n");

        String out = run("--server", serverUrl(), "ssh-key", "add", "--name", "imported", "--path", existingKey.toString());

        assertThat(out).contains("Registered SSH key");
        assertThat(hits).anyMatch(h -> h.startsWith("POST /api/ssh-keys"));
        assertThat(hits).anyMatch(h -> h.contains("\"publicKey\":\"ssh-ed25519 AAAAimported"));
        assertThat(SshKeyPaths.privateKey("imported")).doesNotExist();
    }

    @Test
    void list_printsEmptyMessageWhenNoKeys() {
        String out = run("--server", serverUrl(), "ssh-key", "list");

        assertThat(out).contains("No SSH keys registered");
        assertThat(hits).contains("GET /api/ssh-keys");
    }

    @Test
    void remove_deletesKey() {
        UUID keyId = UUID.randomUUID();
        String out = run("--server", serverUrl(), "ssh-key", "remove", keyId.toString());

        assertThat(out).contains("Removed SSH key " + keyId);
        assertThat(hits).contains("DELETE /api/ssh-keys/" + keyId);
    }
}
