package dev.orchard.gateway.relay;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.auth.KeyAuthenticator;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Server-side command (shell/exec/subsystem) that opens the matching channel on
 * the relay client session and pumps bytes bidirectionally. Client channel uses
 * Sync streaming; one virtual thread per direction.
 */
public class RelayCommand extends AbstractCommandSupport {

    public enum Mode { SHELL, EXEC, SUBSYSTEM }

    private final SeedlingRelay relay;
    private final Mode mode;
    private final String argument;

    private volatile ClientChannel relayChannel;

    public RelayCommand(SeedlingRelay relay, Mode mode, String argument) {
        super(argument, null);
        this.relay = relay;
        this.mode = mode;
        this.argument = argument;
    }

    @Override
    public void run() {
        try {
            ServerSession serverSession = getSession();
            GatewayRoute route = serverSession.getAttribute(KeyAuthenticator.ROUTE_KEY);
            if (route == null) {
                onExit(255);
                return;
            }
            ClientSession client = relay.relaySession(serverSession, route);
            ClientChannel channel = switch (mode) {
                case SHELL -> client.createShellChannel();
                case EXEC -> client.createExecChannel(argument);
                case SUBSYSTEM -> client.createSubsystemChannel(argument);
            };
            channel.setStreaming(StreamingChannel.Streaming.Sync);
            channel.open().verify(10, TimeUnit.SECONDS);
            this.relayChannel = channel;

            // client → seedling: server command stdin → relay channel
            InputStream serverIn = getInputStream();
            OutputStream relayOut = channel.getInvertedIn();
            Thread outPump = Thread.ofVirtual().name("relay-out").start(() -> copy(serverIn, relayOut));

            // seedling → client: relay channel out/err → server command out/err
            InputStream relayIn = channel.getInvertedOut();
            OutputStream serverOut = getOutputStream();
            Thread inPump = Thread.ofVirtual().name("relay-in").start(() -> copy(relayIn, serverOut));
            Thread errPump = Thread.ofVirtual().name("relay-err").start(() -> copy(channel.getInvertedErr(), getErrorStream()));

            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.MINUTES.toMillis(30));
            inPump.join(2_000);
            errPump.join(2_000);
            outPump.join(2_000);

            Integer exit = channel.getExitStatus();
            onExit(exit == null ? 0 : exit);
        } catch (Exception e) {
            log.warn("Relay failed: {}", e.toString());
            onExit(255);
        }
    }

    @Override
    public void destroy(ChannelSession channel) throws Exception {
        // The server channel may tear down mid-waitFor (abrupt client disconnect);
        // force the seedling-side channel closed so pumps unblock and run() returns.
        ClientChannel active = relayChannel;
        if (active != null) {
            active.close(true);
        }
        super.destroy(channel);
    }

    private static void copy(InputStream in, OutputStream out) {
        try (in; out) {
            in.transferTo(out);
        } catch (IOException e) {
            // channel closed on one side; the peer close propagates the rest
        }
    }
}
