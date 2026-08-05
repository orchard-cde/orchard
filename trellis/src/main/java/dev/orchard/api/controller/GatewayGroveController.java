package dev.orchard.api.controller;

import dev.orchard.api.dto.AuthorizeOwnerRequest;
import dev.orchard.api.dto.GatewayGroveResponse;
import dev.orchard.api.dto.GatewayKeyResponse;
import dev.orchard.api.service.GatewayGroveService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Internal API consumed by the SSH gateway (dev.orchard.gateway). Protected by
 * the standard oauth2ResourceServer chain — the gateway authenticates with a
 * client_credentials service token (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayGroveController {

    private final GatewayGroveService gatewayGroveService;

    public GatewayGroveController(GatewayGroveService gatewayGroveService) {
        this.gatewayGroveService = gatewayGroveService;
    }

    /**
     * Routes a grove for the gateway. 200 route, 404 unknown grove, 409 when
     * the grove exists but its seedling is not running (not routable).
     */
    @GetMapping("/groves/{groveId}")
    public ResponseEntity<GatewayGroveResponse> resolveGrove(@PathVariable UUID groveId) {
        var route = gatewayGroveService.resolveRoute(groveId);
        if (route.isPresent()) {
            return ResponseEntity.ok(route.get());
        }
        return gatewayGroveService.exists(groveId)
            ? ResponseEntity.status(HttpStatus.CONFLICT).build()
            : ResponseEntity.notFound().build();
    }

    /** Registered SSH public keys for a cultivator, for gateway key matching. */
    @GetMapping("/cultivators/{cultivatorId}/keys")
    public ResponseEntity<List<GatewayKeyResponse>> listKeys(@PathVariable UUID cultivatorId) {
        return ResponseEntity.ok(gatewayGroveService.listKeys(cultivatorId));
    }

    /**
     * Owner-token auth: 200 route when the email owns a routable grove,
     * 404 unknown grove, 403 otherwise (unknown email, non-owner, or not ready).
     */
    @PostMapping("/authorize-owner")
    public ResponseEntity<GatewayGroveResponse> authorizeOwner(@Valid @RequestBody AuthorizeOwnerRequest request) {
        var route = gatewayGroveService.authorizeOwner(request.groveId(), request.email());
        if (route.isPresent()) {
            return ResponseEntity.ok(route.get());
        }
        return gatewayGroveService.exists(request.groveId())
            ? ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            : ResponseEntity.notFound().build();
    }
}
