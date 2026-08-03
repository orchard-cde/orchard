package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.AbstractChannel;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
