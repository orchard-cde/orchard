package dev.orchard.api.service;

import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.SeedlingState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal API consumed by the SSH gateway (dev.orchard.gateway). A grove is
 * routable only when its seedling is running (SAPLING) with an IP/port.
 */
@Service
public class GatewayGroveService {

    private final GroveService groveService;
    private final SshPublicKeyService sshPublicKeyService;
    private final CultivatorService cultivatorService;

    public GatewayGroveService(GroveService groveService, SshPublicKeyService sshPublicKeyService,
                               CultivatorService cultivatorService) {
        this.groveService = groveService;
        this.sshPublicKeyService = sshPublicKeyService;
        this.cultivatorService = cultivatorService;
    }

    /** Returns the route for a grove whose seedling is running (routable), if any. */
    public Optional<GatewayGroveResponse> resolveRoute(UUID groveId) {
        return groveService.getGrove(groveId)
            .filter(this::isRoutable)
            .map(this::toResponse);
    }

    /** True when a grove with the given id exists (regardless of readiness). */
    public boolean exists(UUID groveId) {
        return groveService.getGrove(groveId).isPresent();
    }

    /** Registered SSH public keys for a cultivator, for gateway key matching. */
    public List<GatewayKeyResponse> listKeys(UUID cultivatorId) {
        return sshPublicKeyService.listForCultivator(cultivatorId).stream()
            .map(k -> new GatewayKeyResponse(k.id(), k.name(), k.publicKey(), k.fingerprint()))
            .toList();
    }

    /**
     * Returns the route only when the grove is routable AND owned by the
     * cultivator identified by {@code email}.
     */
    public Optional<GatewayGroveResponse> authorizeOwner(UUID groveId, String email) {
        Optional<GatewayGroveResponse> route = resolveRoute(groveId);
        if (route.isEmpty()) {
            return Optional.empty();
        }
        Optional<Cultivator> owner = cultivatorService.findByEmail(email);
        if (owner.isEmpty() || !owner.get().id().equals(route.get().cultivatorId())) {
            return Optional.empty();
        }
        return route;
    }

    private boolean isRoutable(Grove grove) {
        return grove.seedling() != null
            && grove.seedling().ipAddress() != null
            && grove.seedling().state() == SeedlingState.SAPLING;
    }

    private GatewayGroveResponse toResponse(Grove grove) {
        var seedling = grove.seedling();
        return new GatewayGroveResponse(
            grove.id(),
            grove.cultivatorId(),
            seedling.ipAddress(),
            seedling.sshPort(),
            grove.state().name()
        );
    }
}
