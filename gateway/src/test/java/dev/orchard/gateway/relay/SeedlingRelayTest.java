package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.config.GatewayProperties;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeedlingRelayTest {

    @TempDir
    Path tempDir;

    private SshServer seedling;
    private SshClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.stop();
        }
        if (seedling != null) {
            seedling.stop();
        }
    }

    private GatewayProperties properties() {
        GatewayProperties p = new GatewayProperties();
        p.setInternalSshKeyPath(tempDir.resolve("orchard_ed25519").toString());
        return p;
    }

    @Test
    void loadsInternalKeyFromOpenSshPem() throws Exception {
        // Generate via the net.i2p eddsa library directly: the JDK's built-in Ed25519
        // KeyPairGenerator produces sun.security.ec.ed keys that MINA's OpenSSH writer
        // (which encodes via net.i2p.crypto.eddsa.EdDSAPrivateKey) cannot cast.
        KeyPair pair = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        Path keyFile = tempDir.resolve("orchard_ed25519");
        try (var out = java.nio.file.Files.newOutputStream(keyFile)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(pair, "test-key", null, out);
        }

        SeedlingRelay relay = new SeedlingRelay(new SshClient(), properties());
        KeyPair loaded = relay.loadInternalKey();
        assertThat(PublicKeyEntry.toString(loaded.getPublic()))
                .isEqualTo(PublicKeyEntry.toString(pair.getPublic()));
    }

    /**
     * Real seedling + real client session, no Spring context — same pattern as
     * {@link RelayIntegrationTest}. Proves relaySession's cache is actually a cache
     * (same ClientSession instance back, exactly one connect+auth against the
     * seedling) rather than just asserting the attribute key is non-null.
     */
    @Test
    @Timeout(30)
    void relaySessionReturnsSameCachedClientSessionOnSecondCall() throws Exception {
        Fixture fixture = buildFixture();

        ClientSession first = fixture.relay.relaySession(fixture.serverSession, fixture.route);
        ClientSession second = fixture.relay.relaySession(fixture.serverSession, fixture.route);

        assertThat(second).isSameAs(first);
        assertThat(fixture.connectionCount.get())
                .as("second relaySession() call must reuse the cached session, not open a new one")
                .isEqualTo(1);

        first.close(false);
    }

    /**
     * Two channels opening "at once" on the same ServerSession (shell + direct-tcpip
     * forward, say) is the scenario the Javadoc contract ("one client session per server
     * session") has to hold under. A {@link CyclicBarrier} releases both worker threads
     * together so both observe the cache in whatever state it's in at call time, then real
     * network connect+auth latency to the seedling (not a mock) provides the race window a
     * non-atomic check-then-act would fall into. With the synchronized block in place,
     * exactly one connect/auth happens and both threads get the identical cached session.
     */
    @Test
    @Timeout(30)
    void relaySessionIsAtomicUnderConcurrentChannelsOnSameServerSession() throws Exception {
        Fixture fixture = buildFixture();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<ClientSession> task = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return fixture.relay.relaySession(fixture.serverSession, fixture.route);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ClientSession> f1 = pool.submit(task);
            Future<ClientSession> f2 = pool.submit(task);
            ClientSession s1 = f1.get(20, TimeUnit.SECONDS);
            ClientSession s2 = f2.get(20, TimeUnit.SECONDS);

            assertThat(s1).isSameAs(s2);
            assertThat(fixture.connectionCount.get())
                    .as("only one of the two concurrent channels should connect+auth to the seedling")
                    .isEqualTo(1);

            s1.close(false);
        } finally {
            pool.shutdownNow();
        }
    }

    private Fixture buildFixture() throws Exception {
        KeyPair internalKey = new net.i2p.crypto.eddsa.KeyPairGenerator().generateKeyPair();
        Path internalKeyPath = tempDir.resolve("orchard_ed25519");
        try (var out = java.nio.file.Files.newOutputStream(internalKeyPath)) {
            org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE
                    .writePrivateKey(internalKey, "test-key", null, out);
        }

        seedling = SshServer.setUpDefaultServer();
        seedling.setPort(0);
        var hostKeyProvider = new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(
                tempDir.resolve("seedling-host-key-" + UUID.randomUUID()));
        hostKeyProvider.setAlgorithm(SecurityUtils.EDDSA);
        seedling.setKeyPairProvider(hostKeyProvider);
        seedling.setPublickeyAuthenticator((username, offeredKey, session) ->
                "cultivator".equals(username) && offeredKey.equals(internalKey.getPublic()));
        seedling.start();

        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        // SSH publickey auth involves a "query" round-trip (offer the key unsigned to see if
        // the server would accept it) followed by the real signed attempt, so the seedling's
        // publickeyAuthenticator is invoked twice per successful auth - not a usable proxy for
        // "how many times did relaySession() actually connect". sessionCreated fires exactly
        // once per sshClient.connect() call, which is the thing under test.
        AtomicInteger connectionCount = new AtomicInteger();
        client.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                connectionCount.incrementAndGet();
            }
        });
        client.start();

        SeedlingRelay relay = new SeedlingRelay(client, properties());
        relay.start(); // package-private @PostConstruct equivalent; no Spring context here

        GatewayRoute route = new GatewayRoute(
                UUID.randomUUID(), UUID.randomUUID(), "127.0.0.1", seedling.getPort(), "FLOURISHING");
        return new Fixture(relay, fakeServerSession(), route, connectionCount);
    }

    private record Fixture(SeedlingRelay relay, ServerSession serverSession, GatewayRoute route, AtomicInteger connectionCount) {}

    /** Mockito mock backed by a real map so getAttribute/setAttribute round-trip like the real thing. */
    private ServerSession fakeServerSession() {
        ServerSession serverSession = mock(ServerSession.class);
        Map<AttributeRepository.AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
        when(serverSession.getAttribute(any())).thenAnswer(invocation ->
                attributes.get(invocation.getArgument(0)));
        when(serverSession.setAttribute(any(), any())).thenAnswer(invocation ->
                attributes.put(invocation.getArgument(0), invocation.getArgument(1)));
        doAnswer(invocation -> null).when(serverSession).addCloseFutureListener(any());
        return serverSession;
    }
}
