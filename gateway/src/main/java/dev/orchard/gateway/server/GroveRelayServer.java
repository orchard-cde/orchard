package dev.orchard.gateway.server;

import dev.orchard.gateway.auth.KeyAuthenticator;
import dev.orchard.gateway.auth.OwnerTokenAuthenticator;
import dev.orchard.gateway.config.GatewayProperties;
import dev.orchard.gateway.relay.RelayDirectTcpipFactory;
import dev.orchard.gateway.relay.SeedlingRelay;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Boots the MINA SSHD listener. This is the ONLY process that reaches seedling
 * port 22 (loopback, colocated with trellis). Spring Boot's own server.port is
 * admin/actuator only.
 */
@Component
public class GroveRelayServer {

    private static final Logger log = LoggerFactory.getLogger(GroveRelayServer.class);

    private final GatewayProperties properties;
    private final HostKeyProvider hostKeyProvider;
    private final KeyAuthenticator keyAuthenticator;
    private final OwnerTokenAuthenticator ownerTokenAuthenticator;
    private final SeedlingRelay seedlingRelay;

    private SshServer server;

    public GroveRelayServer(GatewayProperties properties, HostKeyProvider hostKeyProvider,
                            KeyAuthenticator keyAuthenticator, OwnerTokenAuthenticator ownerTokenAuthenticator,
                            SeedlingRelay seedlingRelay) {
        this.properties = properties;
        this.hostKeyProvider = hostKeyProvider;
        this.keyAuthenticator = keyAuthenticator;
        this.ownerTokenAuthenticator = ownerTokenAuthenticator;
        this.seedlingRelay = seedlingRelay;
    }

    @PostConstruct
    void start() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(properties.getSshPort());
        server.setKeyPairProvider(hostKeyProvider);
        server.setPublickeyAuthenticator(keyAuthenticator);
        server.setPasswordAuthenticator(ownerTokenAuthenticator);

        server.setShellFactory(seedlingRelay.shellFactory());
        server.setCommandFactory(seedlingRelay.commandFactory());
        server.setSubsystemFactories(List.of(seedlingRelay.subsystemFactory()));

        // Replace MINA's direct-tcpip factory with the two-hop relay.
        List<ChannelFactory> factories = new ArrayList<>(ServerBuilder.DEFAULT_CHANNEL_FACTORIES);
        factories.replaceAll(f -> "direct-tcpip".equals(f.getName())
                ? new RelayDirectTcpipFactory(seedlingRelay)
                : f);
        server.setChannelFactories(factories);

        server.start();
        log.info("Grove SSH relay listening on port {}", server.getPort());
    }

    @PreDestroy
    void stop() throws Exception {
        if (server != null && server.isStarted()) {
            server.stop();
        }
    }

    /** Actual bound port (useful when configured with port 0 in tests). */
    public int getBoundPort() {
        return server != null ? server.getPort() : -1;
    }
}
