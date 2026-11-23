package dev.orchard.trowel.command;

import com.sun.net.httpserver.HttpServer;
import dev.orchard.trowel.Trowel;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCommandTest {

    private static final String META =
        "{\"name\":\"GITHUB_TOKEN\",\"createdAt\":\"2026-11-19T10:00:00Z\","
            + "\"updatedAt\":\"2026-11-19T10:00:00Z\",\"rotatedAt\":\"2026-11-19T10:00:00Z\"}";

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream origOut;
    private PrintStream origErr;
    private InputStream origIn;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/secrets", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = META.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        origOut = System.out;
        origErr = System.err;
        origIn = System.in;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void tearDown() {
        System.setOut(origOut);
        System.setErr(origErr);
        System.setIn(origIn);
        server.stop(0);
    }

    private int run(String... args) {
        String[] base = {"-s", "http://127.0.0.1:" + server.getAddress().getPort(),
            "--cultivator", UUID.randomUUID().toString(), "secret"};
        String[] all = new String[base.length + args.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(args, 0, all, base.length, args.length);
        return new CommandLine(new Trowel()).execute(all);
    }

    @Test
    void set_readsValueFromFile_sendsPut(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("v");
        Files.writeString(file, "hunter2");

        int exit = run("set", "GITHUB_TOKEN", "--value-file", file.toString());

        assertThat(exit).isZero();
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/api/secrets/GITHUB_TOKEN");
        assertThat(body.get()).isEqualTo("{\"value\":\"hunter2\"}");
        assertThat(out.toString()).contains("Stored GITHUB_TOKEN").doesNotContain("hunter2");
        assertThat(err.toString()).doesNotContain("hunter2");
    }

    @Test
    void set_readsValueFromStdin_stripsOneNewline() {
        System.setIn(new ByteArrayInputStream("hunter2\n\n".getBytes(StandardCharsets.UTF_8)));

        int exit = run("set", "GITHUB_TOKEN");

        assertThat(exit).isZero();
        assertThat(path.get()).isEqualTo("/api/secrets/GITHUB_TOKEN");
        assertThat(body.get()).isEqualTo("{\"value\":\"hunter2\\n\"}");
        assertThat(out.toString()).contains("Stored GITHUB_TOKEN").doesNotContain("hunter2");
    }

    @Test
    void set_readsValueFromStdin_stripsTrailingCrlfAsOneNewline() {
        System.setIn(new ByteArrayInputStream("hunter2\r\n\r\n".getBytes(StandardCharsets.UTF_8)));

        int exit = run("set", "GITHUB_TOKEN");

        assertThat(exit).isZero();
        assertThat(body.get()).isEqualTo("{\"value\":\"hunter2\\r\\n\"}");
    }

    @Test
    void set_reservedName_rejectedBeforeRequest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("v");
        Files.writeString(file, "hunter2");

        int exit = run("set", "LD_PRELOAD", "--value-file", file.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("reserved name: LD_PRELOAD");
        assertThat(method.get()).isNull();
    }

    @Test
    void set_valueOnCommandLine_isRejectedWithoutEchoingIt() {
        int exit = run("set", "GITHUB_TOKEN", "hunter2");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Usage").doesNotContain("hunter2");
        assertThat(out.toString()).doesNotContain("hunter2");
        assertThat(method.get()).isNull();
    }

    @Test
    void set_valueStartingWithDash_notEchoed() {
        int exit = run("set", "GITHUB_TOKEN", "-----BEGIN PRIVATE KEY-----");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(out.toString()).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(method.get()).isNull();
    }

    @Test
    void set_valueAsOptionSyntax_notEchoed() {
        int exit = run("set", "GITHUB_TOKEN", "--value=hunter2");

        assertThat(exit).isNotZero();
        assertThat(err.toString()).doesNotContain("hunter2");
        assertThat(out.toString()).doesNotContain("hunter2");
        assertThat(method.get()).isNull();
    }

    @Test
    void set_invalidName_rejectedBeforeRequest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("v");
        Files.writeString(file, "hunter2");

        int exit = run("set", "a/b", "--value-file", file.toString());

        assertThat(exit).isNotZero();
        assertThat(err.toString()).contains("Invalid secret name: a/b");
        assertThat(method.get()).isNull();
    }

    @Test
    void remove_invalidName_rejectedBeforeRequest() {
        int exit = run("rm", "-f", "x?y");

        assertThat(exit).isNotZero();
        assertThat(method.get()).isNull();
    }
}
