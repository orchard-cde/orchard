package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.AbstractChannel;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the wiring the brief calls out explicitly: doInit must resolve
 * the route from the server session, open the tunnel on the gateway->seedling client
 * session (never raw TCP), and use the correct originator/target addresses parsed off
 * the wire. The real byte relay (client -> tunnel -> target and back) is exercised by
 * Task 8's end-to-end integration test.
 */
class RelayDirectTcpipChannelTest {

    private static final GatewayRoute ROUTE =
            new GatewayRoute(UUID.randomUUID(), UUID.randomUUID(), "10.0.5.9", 22, "SAPLING");

    @Test
    void doInitOpensTheTunnelOnTheSeedlingClientSessionInsteadOfRawTcp() throws Exception {
        SeedlingRelay relay = mock(SeedlingRelay.class);
        ServerSession serverSession = mock(ServerSession.class);
        when(serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY)).thenReturn(ROUTE);

        ClientSession relaySession = mock(ClientSession.class);
        when(relay.relaySession(serverSession, ROUTE)).thenReturn(relaySession);

        ChannelDirectTcpip tunnel = mock(ChannelDirectTcpip.class);
        when(relaySession.createDirectTcpipChannel(any(), any())).thenReturn(tunnel);
        when(tunnel.getInvertedOut()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(tunnel.getInvertedIn()).thenReturn(new ByteArrayOutputStream());

        OpenFuture tunnelOpenFuture = mock(OpenFuture.class);
        when(tunnel.open()).thenReturn(tunnelOpenFuture);
        when(tunnelOpenFuture.isOpened()).thenReturn(true);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.apache.sshd.common.future.SshFutureListener<OpenFuture> listener =
                    invocation.getArgument(0);
            listener.operationComplete(tunnelOpenFuture);
            return tunnelOpenFuture;
        }).when(tunnelOpenFuture).addListener(any());

        RelayDirectTcpipChannel channel = new RelayDirectTcpipChannel(relay);
        setSession(channel, serverSession);

        OpenFuture result = invokeDoInit(channel, "example.internal", 8080, "127.0.0.1", 54321);

        // The channel resolved the route from the server session and asked the relay for
        // the gateway->seedling session - it did not attempt to connect anywhere itself.
        verify(relay).relaySession(serverSession, ROUTE);
        verify(relaySession).createDirectTcpipChannel(
                new SshdSocketAddress("127.0.0.1", 54321),
                new SshdSocketAddress("example.internal", 8080));
        verify(tunnel).setStreaming(StreamingChannel.Streaming.Sync);
        assertThat(result.isOpened()).isTrue();
    }

    @Test
    void doInitFailsTheOpenFutureWhenTheRelaySessionCannotBeOpened() throws Exception {
        SeedlingRelay relay = mock(SeedlingRelay.class);
        ServerSession serverSession = mock(ServerSession.class);
        when(serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY)).thenReturn(ROUTE);
        RuntimeException boom = new RuntimeException("no route to seedling");
        when(relay.relaySession(serverSession, ROUTE)).thenThrow(boom);

        RelayDirectTcpipChannel channel = new RelayDirectTcpipChannel(relay);
        setSession(channel, serverSession);

        OpenFuture result = invokeDoInit(channel, "example.internal", 8080, "127.0.0.1", 54321);

        assertThat(result.getException()).isSameAs(boom);
    }

    /**
     * Regression test for the reverse (target -> client) pump. writeBuffer() is async and
     * retains its Buffer until the write actually drains; this stub models that by delaying
     * the moment it reads the Buffer's bytes and completes its IoWriteFuture, instead of
     * capturing/completing synchronously like the earlier success-path test does. That gap
     * is exactly what exposes: (a) reusing the shared read array without copying - the next
     * read would clobber the bytes before this delayed read observes them - and (b) issuing
     * the next write before the previous one drained, which real ChannelAsyncOutputStream
     * rejects (WritePendingException) and which this stub simply counts as concurrency > 1.
     */
    @Test
    void startTunnelReaderCopiesEachChunkAndWaitsForEachWriteToDrainBeforeReadingMore() throws Exception {
        byte[] chunk0 = fill(20, (byte) 1);
        byte[] chunk1 = fill(20, (byte) 2);
        byte[] chunk2 = fill(20, (byte) 3);

        List<byte[]> capturedWrites = new CopyOnWriteArrayList<>();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);

        ChannelAsyncOutputStream outMock = mock(ChannelAsyncOutputStream.class);
        when(outMock.writeBuffer(any())).thenAnswer(writeInvocation -> {
            Buffer buffer = writeInvocation.getArgument(0);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);

            IoWriteFuture future = mock(IoWriteFuture.class, Mockito.CALLS_REAL_METHODS);
            when(future.verify(anyLong(), any(org.apache.sshd.common.future.CancelOption[].class))).thenAnswer(verifyInvocation -> {
                // Simulate the real gap between writeBuffer() returning and the bytes
                // actually being drained off the wire - long enough that a reader loop
                // which doesn't wait for this will have already reused/overwritten its
                // shared buffer and issued a second concurrent write by the time we get here.
                Thread.sleep(60);
                byte[] snapshot = new byte[buffer.available()];
                System.arraycopy(buffer.array(), buffer.rpos(), snapshot, 0, snapshot.length);
                capturedWrites.add(snapshot);
                inFlight.decrementAndGet();
                return future;
            });
            return future;
        });

        ChannelDirectTcpip tunnel = mock(ChannelDirectTcpip.class);
        when(tunnel.getInvertedOut()).thenReturn(new ChunkedInputStream(chunk0, chunk1, chunk2));

        RelayDirectTcpipChannel channel = new RelayDirectTcpipChannel(mock(SeedlingRelay.class));
        setTunnel(channel, tunnel);
        setOut(channel, outMock);

        invokeStartTunnelReader(channel);

        long deadline = System.currentTimeMillis() + 5000;
        while (capturedWrites.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(capturedWrites).hasSize(3);
        assertThat(capturedWrites.get(0)).isEqualTo(chunk0);
        assertThat(capturedWrites.get(1)).isEqualTo(chunk1);
        assertThat(capturedWrites.get(2)).isEqualTo(chunk2);
        assertThat(maxInFlight.get())
                .as("only one write may be in flight at a time - the reader must await drain before the next read")
                .isEqualTo(1);
    }

    private static byte[] fill(int length, byte value) {
        byte[] data = new byte[length];
        java.util.Arrays.fill(data, value);
        return data;
    }

    /** Emits each supplied chunk on successive read() calls, then EOF (-1). */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[][] chunks;
        private int index;

        ChunkedInputStream(byte[]... chunks) {
            this.chunks = chunks;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException("not used by the reverse pump");
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (index >= chunks.length) {
                return -1;
            }
            byte[] chunk = chunks[index++];
            System.arraycopy(chunk, 0, b, off, chunk.length);
            return chunk.length;
        }
    }

    private static void setTunnel(RelayDirectTcpipChannel channel, ChannelDirectTcpip tunnel) throws Exception {
        Field field = RelayDirectTcpipChannel.class.getDeclaredField("tunnel");
        field.setAccessible(true);
        field.set(channel, tunnel);
    }

    private static void setOut(RelayDirectTcpipChannel channel, ChannelAsyncOutputStream out) throws Exception {
        Field field = RelayDirectTcpipChannel.class.getDeclaredField("out");
        field.setAccessible(true);
        field.set(channel, out);
    }

    private static void invokeStartTunnelReader(RelayDirectTcpipChannel channel) throws Exception {
        Method method = RelayDirectTcpipChannel.class.getDeclaredMethod("startTunnelReader");
        method.setAccessible(true);
        method.invoke(channel);
    }

    private static void setSession(RelayDirectTcpipChannel channel, ServerSession session) throws Exception {
        Field sessionField = AbstractChannel.class.getDeclaredField("sessionInstance");
        sessionField.setAccessible(true);
        sessionField.set(channel, session);
    }

    private static OpenFuture invokeDoInit(
            RelayDirectTcpipChannel channel, String host, int port, String originatorIp, int originatorPort)
            throws Exception {
        Buffer buffer = new ByteArrayBuffer();
        buffer.putString(host);
        buffer.putInt(port);
        buffer.putString(originatorIp);
        buffer.putInt(originatorPort);

        Method doInit = RelayDirectTcpipChannel.class.getDeclaredMethod("doInit", Buffer.class);
        doInit.setAccessible(true);
        return (OpenFuture) doInit.invoke(channel, buffer);
    }
}
