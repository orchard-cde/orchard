package dev.orchard.gateway.auth;

import dev.orchard.core.model.SshPublicKey;
import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import dev.orchard.gateway.service.GroveResolver;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.PublicKey;

/**
 * Publickey auth for the SSH gateway: the offered key must be registered to the
 * grove's cultivator (fingerprint match via the core algorithm). The resolved
 * route is stashed on the session for the relay.
 */
@Component
public class KeyAuthenticator implements PublickeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(KeyAuthenticator.class);

    public static final AttributeRepository.AttributeKey<GatewayRoute> ROUTE_KEY = new AttributeRepository.AttributeKey<>();

    private final GroveResolver groveResolver;
    private final TrellisApiClient trellisApiClient;

    public KeyAuthenticator(GroveResolver groveResolver, TrellisApiClient trellisApiClient) {
        this.groveResolver = groveResolver;
        this.trellisApiClient = trellisApiClient;
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        try {
            var route = groveResolver.resolve(username);
            if (route.isEmpty()) {
                return false;
            }
            String offeredFingerprint = SshPublicKey.fingerprint(PublicKeyEntry.toString(key));
            boolean matched = trellisApiClient.listKeys(route.get().cultivatorId()).stream()
                    .anyMatch(k -> k.fingerprint().equals(offeredFingerprint));
            if (matched) {
                session.setAttribute(ROUTE_KEY, route.get());
            }
            return matched;
        } catch (Exception e) {
            log.debug("Publickey auth rejected: trellis call failed: {}", e.getMessage());
            return false;
        }
    }
}
