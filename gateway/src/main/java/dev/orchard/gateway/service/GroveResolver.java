package dev.orchard.gateway.service;

import dev.orchard.gateway.api.GatewayRoute;
import dev.orchard.gateway.api.TrellisApiClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an SSH username (the grove id) to a routable grove route.
 * Rejects malformed ids without touching trellis.
 */
@Component
public class GroveResolver {

    private final TrellisApiClient trellisApiClient;

    public GroveResolver(TrellisApiClient trellisApiClient) {
        this.trellisApiClient = trellisApiClient;
    }

    public Optional<GatewayRoute> resolve(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        try {
            return trellisApiClient.resolveGrove(UUID.fromString(username));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
