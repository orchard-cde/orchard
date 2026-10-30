package dev.orchard.trowel.command;

import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.Trowel;
import dev.orchard.trowel.ssh.SshKeyPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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

    private void writeLocalConfig(String sshKey) throws Exception {
        var targets = new java.util.LinkedHashMap<String, dev.orchard.trowel.config.OrchardConfig.Target>();
        targets.put("local", new dev.orchard.trowel.config.OrchardConfig.Target(
            serverUrl(), "c-uuid", null, null, null, null, sshKey));
        dev.orchard.trowel.config.ConfigLoader.save(new dev.orchard.trowel.config.OrchardConfig("local", targets));
    }

    private String savedSshKey() {
        return dev.orchard.trowel.config.ConfigLoader.load().targets().get("local").sshKey();
    }

    @Test
    void sshKeyAdd_generated_setsTargetSshKey() throws Exception {
        writeLocalConfig(null);

        run("ssh-key", "add", "--name", "dev-server");

        assertThat(savedSshKey()).isEqualTo("dev-server");
    }

    private int runExit(String... args) {
        java.io.PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            return new CommandLine(new Trowel()).execute(args);
        } finally {
            System.setErr(err);
        }
    }

    @Test
    void sshKeyAdd_noConfig_recordsKey() {
        String out = run("--server", serverUrl(), "ssh-key", "add", "--name", "dev-server");

        assertThat(out).contains("Created trowel config at");
        var config = dev.orchard.trowel.config.ConfigLoader.load();
        assertThat(config.active()).isEqualTo("local");
        assertThat(config.targets().get("local").sshKey()).isEqualTo("dev-server");
        assertThat(config.targets().get("local").server()).isEqualTo(serverUrl());
    }

    @Test
    void sshKeyAdd_noConfig_defaultServer_recordsLocalhost() {
        var planned = SshKeyCommand.planSshKey("dev-server", null, new Trowel().getServerUrl());

        var target = planned.targets().get("local");
        assertThat(target.server()).isEqualTo("http://localhost:17778");
        assertThat(target.sshKey()).isEqualTo("dev-server");
    }

    @Test
    void sshKeyAdd_unknownTarget_failsLoudly() throws Exception {
        writeLocalConfig(null);

        int exit = runExit("--server", serverUrl(), "--target", "nope", "ssh-key", "add");

        assertThat(exit).isNotZero();
        assertThat(savedSshKey()).isNull();
        assertThat(hits).isEmpty();
    }

    @Test
    void sshKeyAdd_withPath_leavesSshKeyUnchanged() throws Exception {
        writeLocalConfig("kept");
        Path existingKey = tempDir.resolve("imported.pub");
        Files.writeString(existingKey, "ssh-ed25519 AAAAimported imported@elsewhere\n");

        run("ssh-key", "add", "--name", "imported", "--path", existingKey.toString());

        assertThat(savedSshKey()).isEqualTo("kept");
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
        String out = run("--server", serverUrl(), "ssh-key", "remove", "--force", keyId.toString());

        assertThat(out).contains("Removed SSH key " + keyId);
        assertThat(hits).contains("DELETE /api/ssh-keys/" + keyId);
    }

    @Test
    void remove_withoutForce_deletesOnAffirmativeConfirmation() {
        UUID keyId = UUID.randomUUID();
        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
        try {
            String out = run("--server", serverUrl(), "ssh-key", "remove", keyId.toString());

            assertThat(out).contains("Are you sure you want to remove SSH key " + keyId);
            assertThat(out).contains("Removed SSH key " + keyId);
            assertThat(hits).contains("DELETE /api/ssh-keys/" + keyId);
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void remove_withoutForce_abortsOnDecline() {
        UUID keyId = UUID.randomUUID();
        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));
        try {
            String out = run("--server", serverUrl(), "ssh-key", "remove", keyId.toString());

            assertThat(out).contains("Are you sure you want to remove SSH key " + keyId);
            assertThat(out).contains("Cancelled.");
            assertThat(hits).doesNotContain("DELETE /api/ssh-keys/" + keyId);
        } finally {
            System.setIn(originalIn);
        }
    }
}
