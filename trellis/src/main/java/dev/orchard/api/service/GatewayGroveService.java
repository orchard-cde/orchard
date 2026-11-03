package dev.orchard.api.service;

import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.trellis.config.GatewayAddress;
import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.Cultivator;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.FruitState;
import dev.orchard.core.model.GroveState;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.container.ContainerConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal API consumed by the SSH gateway (dev.orchard.gateway). A grove is
 * routable only when it is FLOURISHING and either its VM plot is running (ROOTED) with a host, or
 * its container plot is ROOTED with a RIPE primary fruit that has a container id.
 */
@Service
public class GatewayGroveService {

    private final GroveService groveService;
    private final SshPublicKeyService sshPublicKeyService;
    private final CultivatorService cultivatorService;
    private final GatewayAddress gatewayAddress;
    private final ContainerConfig containerConfig;

    public GatewayGroveService(GroveService groveService, SshPublicKeyService sshPublicKeyService,
                               CultivatorService cultivatorService, GatewayAddress gatewayAddress,
                               ContainerConfig containerConfig) {
        this.gatewayAddress = gatewayAddress;
        this.containerConfig = containerConfig;
        this.groveService = groveService;
        this.sshPublicKeyService = sshPublicKeyService;
        this.cultivatorService = cultivatorService;
    }

    /** Returns the route for a grove that is routable, if any. */
    public Optional<GatewayGroveResponse> resolveRoute(UUID groveId) {
        return groveService.getGrove(groveId).flatMap(this::route);
    }

    /** The single connection string for a grove: always through the gateway, present iff routable. */
    public Optional<String> connectionString(Grove grove) {
        if (!isRoutable(grove)) {
            return Optional.empty();
        }
        return Optional.of("ssh -p %d %s@%s".formatted(
            gatewayAddress.sshPort(), grove.id(), gatewayAddress.sshHost()));
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

    boolean isRoutable(Grove grove) {
        return route(grove).isPresent();
    }

    private Optional<GatewayGroveResponse> route(Grove grove) {
        if (grove.state() != GroveState.FLOURISHING) {
            return Optional.empty();
        }
        if (grove.plot() instanceof ContainerPlot container) {
            return containerRoute(grove, container);
        }
        return grove.sshReachableVm().map(vm -> new GatewayGroveResponse(
            grove.id(), grove.cultivatorId(), grove.state().name(), "vm",
            vm.host(), vm.sshPort(), null, null, null, null, null));
    }

    private Optional<GatewayGroveResponse> containerRoute(Grove grove, ContainerPlot container) {
        Fruit fruit = grove.primaryFruit();
        if (container.state() != PlotState.ROOTED || fruit == null
            || fruit.state() != FruitState.RIPE || fruit.containerId() == null) {
            return Optional.empty();
        }
        return Optional.of(new GatewayGroveResponse(
            grove.id(), grove.cultivatorId(), grove.state().name(), "container",
            null, null, container.runtime(), fruit.containerId(),
            fruit.remoteUser(), fruit.workspaceFolder(), containerConfig.engineUrl()));
    }
}
