package dev.orchard.gateway.relay;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.Session;

/** Replaces MINA's default direct-tcpip factory with the two-hop relay. */
public class RelayDirectTcpipFactory implements ChannelFactory {

    private final SeedlingRelay relay;

    public RelayDirectTcpipFactory(SeedlingRelay relay) {
        this.relay = relay;
    }

    @Override
    public String getName() {
        return "direct-tcpip";
    }

    @Override
    public Channel createChannel(Session session) {
        return new RelayDirectTcpipChannel(relay);
    }
}
