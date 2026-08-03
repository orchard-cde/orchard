package dev.orchard.gateway.relay;

import com.sun.net.httpserver.HttpServer;
import dev.orchard.core.model.SshPublicKey;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.auth.FenceTokenClient;
import dev.orchard.gateway.auth.KeyAuthenticator;
import dev.orchard.gateway.config.GatewayProperties;
import dev.orchard.gateway.service.GroveResolver;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real end-to-end relay proof, no Spring context: boots a fake "seedling" MINA
 * sshd, the gateway's MINA sshd (real {@link KeyAuthenticator}, real
 * {@link SeedlingRelay}), and a tiny embedded HTTP server standing in for
 * trellis + fence's token endpoint. A real {@link SshClient} then drives both
 * an exec round-trip and a two-hop {@code direct-tcpip} port-forward through
 * the gateway to a plain TCP echo target the seedling connects to.
 *
 * <p>Deliberately lives in the same package as {@link SeedlingRelay} so it can
 * call its package-private {@code start()} lifecycle method directly, the
 * same way Spring's {@code @PostConstruct} would, without needing a Spring
 * context.
 */
class RelayIntegrationTest {

    private static final byte[] EXEC_PAYLOAD = deterministicPayload(200_000, (byte) 7);
    // Comfortably past the ~2MB default SSH channel window: without
    // RelayDirectTcpipChannel.doWriteData releasing the local window after each forwarded
    // chunk, the client -> gateway window is consumed but never replenished and the forward
    // hangs once it hits zero, failing this test via @Timeout instead of completing.
    private static final int FORWARD_PAYLOAD_SIZE = 3_000_000;

    @TempDir
    Path tempDir;

    private HttpServer trellisStub;
    private SshServer seedling;
    private SshServer gateway;
    private SshClient client;

    private UUID groveId;
    private UUID cultivatorId;
    private KeyPair clientIdentity;
    private String clientFingerprint;

    @BeforeEach
    void setUp() throws Exception {
        groveId = UUID.randomUUID();
        cultivatorId = UUID.randomUUID();
        clientIdentity = edKeyPair();
        String clientWireLine = PublicKeyEntry.toString(clientIdentity.getPublic());
        clientFingerprint = SshPublicKey.fingerprint(clientWireLine);

        KeyPair internalKey = edKeyPair();
        Path internalKeyPath = tempDir.resolve("orchard_ed25519");
        writeOpenSshKey(internalKey, internalKeyPath);

        seedling = buildSeedling(internalKey.getPublic());
        seedling.start();

        trellisStub = startTrellisStub(groveId, cultivatorId, seedling.getPort(), clientWireLine, clientFingerprint);

        GatewayProperties properties = gatewayProperties(internalKeyPath, trellisStub);
        KeyAuthenticator keyAuthenticator = buildKeyAuthenticator(properties);

        SeedlingRelay relay = new SeedlingRelay(buildSshClient(), properties);
        relay.start(); // package-private @PostConstruct equivalent; no Spring context here

        gateway = buildGateway(keyAuthenticator, relay);
        gateway.start();

        client = buildSshClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (gateway != null) {
            gateway.stop();
        }
        if (seedling != null) {
            seedling.stop();
        }
        if (trellisStub != null) {
            trellisStub.stop(0);
        }
    }

    @Test
    @Timeout(30)
    void execCommandIsRelayedToSeedlingWithByteExactStdout() throws Exception {
        try (ClientSession session = authenticatedSession()) {
            ClientChannel channel = session.createExecChannel("emit-payload");
            channel.setStreaming(StreamingChannel.Streaming.Sync);
            channel.open().verify(10, TimeUnit.SECONDS);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(20));

            byte[] stdout = channel.getInvertedOut().readAllBytes();

            assertThat(stdout).isEqualTo(EXEC_PAYLOAD);
            assertThat(channel.getExitStatus()).isEqualTo(0);
        }
    }

    @Test
    @Timeout(30)
    void directTcpipForwardRoundTripsIntactThroughSeedlingToTarget() throws Exception {
        byte[] payload = deterministicPayload(FORWARD_PAYLOAD_SIZE, (byte) 41);

        try (ServerSocket echoServer = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread echoThread = Thread.ofVirtual().name("test-echo-server").start(() -> runEchoServer(echoServer));

            try (ClientSession session = authenticatedSession()) {
                SshdSocketAddress bound = session.startLocalPortForwarding(
                        new SshdSocketAddress("127.0.0.1", 0),
                        new SshdSocketAddress("127.0.0.1", echoServer.getLocalPort()));

                byte[] received = echoThroughForward(bound.getPort(), payload);
                assertThat(received).isEqualTo(payload);

                session.stopLocalPortForwarding(bound);
            } finally {
                echoServer.close();
                echoThread.join(5_000);
            }
        }
    }

    @Test
    @Timeout(30)
    void unregisteredKeyIsRejected() throws Exception {
        KeyPair impostor = edKeyPair();

        try (ClientSession session = client.connect(groveId.toString(), "127.0.0.1", gateway.getPort())
                .verify(10, TimeUnit.SECONDS).getSession()) {
            session.addPublicKeyIdentity(impostor);
            // MINA exhausts auth methods and throws rather than completing the future with
            // isSuccess()==false - either way, the key that isn't in the stubbed trellis
            // fingerprint list must not authenticate.
            assertThatThrownBy(() -> session.auth().verify(10, TimeUnit.SECONDS))
                    .isInstanceOf(org.apache.sshd.common.SshException.class);
            assertThat(session.isAuthenticated()).isFalse();
        }
    }

    // ---------------------------------------------------------------- helpers

    private ClientSession authenticatedSession() throws Exception {
        ClientSession session = client.connect(groveId.toString(), "127.0.0.1", gateway.getPort())
                .verify(10, TimeUnit.SECONDS).getSession();
        session.addPublicKeyIdentity(clientIdentity);
        AuthFuture auth = session.auth().verify(10, TimeUnit.SECONDS);
        assertThat(auth.isSuccess()).as("ed25519 auth against stubbed trellis fingerprint " + clientFingerprint).isTrue();
        return session;
    }

    /** Concurrent write+read over the forwarded socket to avoid a full-payload deadlock on SSH channel windows. */
    private static byte[] echoThroughForward(int forwardedPort, byte[] payload) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", forwardedPort), 10_000);

            CompletableFuture<byte[]> readFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    ByteArrayOutputStream received = new ByteArrayOutputStream(payload.length);
                    byte[] buf = new byte[8192];
                    int total = 0;
                    while (total < payload.length) {
                        int n = socket.getInputStream().read(buf);
                        if (n < 0) {
                            break;
                        }
                        received.write(buf, 0, n);
                        total += n;
                    }
                    return received.toByteArray();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();

            return readFuture.get(20, TimeUnit.SECONDS);
        }
    }

    private static void runEchoServer(ServerSocket serverSocket) {
        try {
            while (!serverSocket.isClosed()) {
                Socket conn = serverSocket.accept();
                Thread.ofVirtual().name("test-echo-conn").start(() -> {
                    try (Socket c = conn) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = c.getInputStream().read(buf)) >= 0) {
                            c.getOutputStream().write(buf, 0, n);
                            c.getOutputStream().flush();
                        }
                    } catch (Exception ignored) {
                        // connection torn down by the test
                    }
                });
            }
        } catch (Exception ignored) {
            // server closed
        }
    }

    private static byte[] deterministicPayload(int size, byte salt) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + salt) % 256);
        }
        return data;
    }

    private static KeyPair edKeyPair() {
        // MINA's ssh-ed25519 wire codec only recognizes net.i2p.crypto.eddsa key types,
        // not the JDK-native java.security.interfaces.EdECPublicKey produced by
        // KeyPairGenerator.getInstance("Ed25519") - see KeyAuthenticatorTest for the
        // same constraint on the "real client" side of this test.
        return new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
    }

    private static void writeOpenSshKey(KeyPair pair, Path path) throws Exception {
        try (var out = Files.newOutputStream(path)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test-key", null, out);
        }
    }

    private org.apache.sshd.common.keyprovider.KeyPairProvider testHostKeyProvider() {
        // Same fix as HostKeyProvider (Task 7): SimpleGeneratorHostKeyProvider.setAlgorithm
        // takes a JCA KeyPairGenerator algorithm name ("EdDSA" / SecurityUtils.EDDSA), not
        // the SSH wire key-type identifier (KeyPairProvider.SSH_ED25519) - the latter throws
        // NoSuchAlgorithmException.
        var provider = new SimpleGeneratorHostKeyProvider(tempDir.resolve("host-key-" + UUID.randomUUID()));
        provider.setAlgorithm(SecurityUtils.EDDSA);
        return provider;
    }

    private SshServer buildSeedling(java.security.PublicKey acceptedInternalKey) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(testHostKeyProvider());
        // Only the gateway's real internal identity is accepted - proves SeedlingRelay
        // actually presents the configured key, not just "any key".
        server.setPublickeyAuthenticator((username, offeredKey, session) ->
                "cultivator".equals(username) && offeredKey.equals(acceptedInternalKey));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.setCommandFactory((channel, command) -> new AbstractCommandSupport(command, null) {
            @Override
            public void run() {
                try {
                    if ("emit-payload".equals(command)) {
                        getOutputStream().write(EXEC_PAYLOAD);
                        getOutputStream().flush();
                        onExit(0);
                    } else {
                        onExit(127);
                    }
                } catch (Exception e) {
                    onExit(1);
                }
            }
        });
        return server;
    }

    private SshServer buildGateway(KeyAuthenticator keyAuthenticator, SeedlingRelay relay) {
        SshServer server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(testHostKeyProvider());
        server.setPublickeyAuthenticator(keyAuthenticator);
        server.setShellFactory(relay.shellFactory());
        server.setCommandFactory(relay.commandFactory());

        // Same direct-tcpip swap GroveRelayServer performs in production.
        List<ChannelFactory> factories = new ArrayList<>(ServerBuilder.DEFAULT_CHANNEL_FACTORIES);
        factories.replaceAll(f -> "direct-tcpip".equals(f.getName())
                ? new RelayDirectTcpipFactory(relay)
                : f);
        server.setChannelFactories(factories);
        return server;
    }

    private SshClient buildSshClient() {
        SshClient c = SshClient.setUpDefaultClient();
        c.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        c.start();
        return c;
    }

    private GatewayProperties gatewayProperties(Path internalKeyPath, HttpServer trellisStub) {
        GatewayProperties p = new GatewayProperties();
        p.setInternalSshKeyPath(internalKeyPath.toString());
        String baseUrl = "http://127.0.0.1:" + trellisStub.getAddress().getPort();
        p.getTrellis().setBaseUrl(baseUrl);
        p.getFence().setIssuerUri(baseUrl);
        p.getOauth2().setClientId("orchard-gateway");
        p.getOauth2().setClientSecret("test-secret");
        return p;
    }

    private KeyAuthenticator buildKeyAuthenticator(GatewayProperties properties) {
        FenceTokenClient fenceTokenClient = new FenceTokenClient(
                org.springframework.web.client.RestClient.builder().build(), properties);
        org.springframework.web.client.RestClient trellisRestClient = TrellisApiClient.buildRestClient(
                properties.getTrellis().getBaseUrl(), fenceTokenClient);
        TrellisApiClient trellisApiClient = new TrellisApiClient(trellisRestClient, new tools.jackson.databind.ObjectMapper());
        GroveResolver groveResolver = new GroveResolver(trellisApiClient);
        return new KeyAuthenticator(groveResolver, trellisApiClient);
    }

    /**
     * Stands in for trellis's {@code /api/gateway/**} plus fence's {@code /oauth2/token} -
     * a real (loopback) HTTP server rather than a strict-call-count mock, since MINA may
     * probe {@link KeyAuthenticator} more than once per auth attempt.
     */
    private static HttpServer startTrellisStub(
            UUID groveId, UUID cultivatorId, int seedlingPort, String clientWireLine, String clientFingerprint) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/oauth2/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = "{\"access_token\":\"stub-service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
            sendJson(exchange, 200, body);
        });

        String groveJson = ("{\"groveId\":\"%s\",\"cultivatorId\":\"%s\",\"seedlingIp\":\"127.0.0.1\","
                + "\"seedlingPort\":%d,\"state\":\"FLOURISHING\"}")
                .formatted(groveId, cultivatorId, seedlingPort);
        server.createContext("/api/gateway/groves/" + groveId, exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, groveJson);
        });

        String keysJson = ("[{\"id\":\"%s\",\"name\":\"laptop\",\"publicKey\":\"%s\",\"fingerprint\":\"%s\"}]")
                .formatted(UUID.randomUUID(), jsonEscape(clientWireLine), clientFingerprint);
        server.createContext("/api/gateway/cultivators/" + cultivatorId + "/keys", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, keysJson);
        });

        server.setExecutor(null);
        server.start();
        return server;
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
