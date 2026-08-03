package dev.orchard.gateway.relay;

import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RelayDirectTcpipFactoryTest {

    @Test
    void nameMatchesTheChannelTypeMinaDispatchesOnChannelOpen() {
        RelayDirectTcpipFactory factory = new RelayDirectTcpipFactory(mock(SeedlingRelay.class));
        assertThat(factory.getName()).isEqualTo("direct-tcpip");
    }

    @Test
    void createChannelReturnsTheRelayChannelBoundToTheSameRelay() {
        SeedlingRelay relay = mock(SeedlingRelay.class);
        RelayDirectTcpipFactory factory = new RelayDirectTcpipFactory(relay);

        var channel = factory.createChannel(mock(ServerSession.class));

        assertThat(channel).isInstanceOf(RelayDirectTcpipChannel.class);
    }
}
