package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.ChannelAsyncOutputStream;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.channel.AbstractServerChannel;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * Server-side direct-tcpip channel that tunnels the forward through the relay
 * client session (client -> gateway -> seedling -> target). Parses the target the
 * same way TcpipServerChannel.doInit does; the gateway never opens TCP itself -
 * instead a client-side ChannelDirectTcpip is opened on the gateway->seedling
 * session and bytes are pumped between the two channels.
 */
public class RelayDirectTcpipChannel extends AbstractServerChannel {

    private final SeedlingRelay relay;
    private SshdSocketAddress target;
    private SshdSocketAddress originator;
    private ChannelAsyncOutputStream out;
    private ChannelDirectTcpip tunnel;

    public RelayDirectTcpipChannel(SeedlingRelay relay) {
        super("direct-tcpip", Collections.emptyList(), null);
        this.relay = relay;
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        // Same wire layout as TcpipServerChannel.doInit (verified against 2.19.0):
        String hostToConnect = buffer.getString();
        int portToConnect = buffer.getInt();
        String originatorIp = buffer.getString();
        int originatorPort = buffer.getInt();
        this.target = new SshdSocketAddress(hostToConnect, portToConnect);
        this.originator = new SshdSocketAddress(originatorIp, originatorPort);

        OpenFuture f = new DefaultOpenFuture(this, this);
        try {
            this.out = new ChannelAsyncOutputStream(this, SshConstants.SSH_MSG_CHANNEL_DATA);

            ServerSession serverSession = (ServerSession) getSession();
            GatewayRoute route = serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY);
            ClientSession client = relay.relaySession(serverSession, route);
            // ChannelDirectTcpip's constructor takes only (SshdSocketAddress, SshdSocketAddress)
            // - no ClientSession parameter. The channel is registered on that session via
            // ClientSession.createDirectTcpipChannel, then opened explicitly below.
            tunnel = client.createDirectTcpipChannel(originator, target);
            tunnel.setStreaming(StreamingChannel.Streaming.Sync);
            tunnel.open().addListener(future -> {
                if (future.isOpened()) {
                    signalChannelOpenSuccess();
                    f.setOpened();
                    startTunnelReader();
                } else {
                    signalChannelOpenFailure(future.getException());
                    f.setException(future.getException());
                    close(true);
                }
            });
        } catch (Exception e) {
            f.setException(e);
            close(true);
        }
        return f;
    }

    private void startTunnelReader() {
        // Bytes arriving from the target (via the relay) go back to the SSH client
        // through the server channel's ChannelAsyncOutputStream. getInvertedOut() is the
        // InputStream side of the tunnel channel (bytes the target sent back).
        Thread.ofVirtual().name("relay-tcpip-in").start(() -> {
            try {
                InputStream in = tunnel.getInvertedOut();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    ByteArrayBuffer packet = new ByteArrayBuffer(buf, 0, n, false);
                    out.writeBuffer(packet).addListener(future -> { });
                }
            } catch (Exception e) {
                close(false);
            }
        });
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        // Bytes from the SSH client -> relay channel -> seedling -> target.
        // getInvertedIn() is the OutputStream side of the tunnel channel (bytes to send).
        if (tunnel != null) {
            var outStream = tunnel.getInvertedIn();
            outStream.write(data, off, (int) len);
            outStream.flush();
        }
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new UnsupportedOperationException("direct-tcpip channel does not support extended data");
    }

    @Override
    public void handleWindowAdjust(Buffer buffer) throws IOException {
        super.handleWindowAdjust(buffer);
        if (out != null) {
            out.onWindowExpanded();
        }
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .close(out)
                .close(super.getInnerCloseable())
                .close(tunnel)
                .build();
    }
}
