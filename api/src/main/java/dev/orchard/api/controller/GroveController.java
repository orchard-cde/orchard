package dev.orchard.api.controller;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.api.service.GroveService;
import dev.orchard.core.model.Grove;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groves")
public class GroveController {

    private final GroveService groveService;

    public GroveController(GroveService groveService) {
        this.groveService = groveService;
    }

    @PostMapping
    public ResponseEntity<GroveResponse> plantGrove(
            HttpServletRequest request,
            @RequestHeader(value = "X-Cultivator-Id", required = false) UUID headerCultivatorId,
            @Valid @RequestBody CreateGroveRequest groveRequest) {
        UUID cultivatorId = resolveCultivatorId(request, headerCultivatorId);
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Grove grove = groveService.plantGrove(cultivatorId, groveRequest);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(GroveResponse.fromModel(grove));
    }

    @GetMapping("/{groveId}")
    public ResponseEntity<GroveResponse> getGrove(@PathVariable UUID groveId) {
        return groveService.getGrove(groveId)
            .map(grove -> ResponseEntity.ok(GroveResponse.fromModel(grove)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<GroveResponse>> listGroves(
            HttpServletRequest request,
            @RequestHeader(value = "X-Cultivator-Id", required = false) UUID headerCultivatorId,
            @RequestParam(defaultValue = "false") boolean all) {
        UUID cultivatorId = resolveCultivatorId(request, headerCultivatorId);
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<GroveResponse> groves = groveService.getGrovesForCultivator(cultivatorId, all)
            .stream()
            .map(GroveResponse::fromModel)
            .toList();
        return ResponseEntity.ok(groves);
    }

    @DeleteMapping("/{groveId}")
    public ResponseEntity<Void> clearGrove(@PathVariable UUID groveId) {
        groveService.clearGrove(groveId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns an SSH config block for connecting to the grove's seedling.
     * This is consumed by the VS Code extension to set up Remote-SSH connections.
     */
    @GetMapping(value = "/{groveId}/ssh-config", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getSshConfig(@PathVariable UUID groveId) {
        return groveService.getGrove(groveId)
            .map(grove -> {
                if (grove.seedling() == null || grove.seedling().ipAddress() == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("Grove seedling is not ready yet");
                }

                var seedling = grove.seedling();
                String hostName = "orchard-" + grove.name().replaceAll("[^a-zA-Z0-9-]", "-");
                String identityFile = resolveSshKeyPath();
                String sshConfig = String.format("""
                    # Orchard Grove: %s
                    Host %s
                      HostName %s
                      Port %d
                      User cultivator
                      IdentityFile %s
                      StrictHostKeyChecking no
                      UserKnownHostsFile /dev/null
                      ForwardAgent yes
                    """,
                    grove.name(),
                    hostName,
                    seedling.ipAddress(),
                    seedling.sshPort(),
                    identityFile
                );

                return ResponseEntity.ok(sshConfig);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{groveId}/actions/stop")
    public ResponseEntity<GroveResponse> stopGrove(@PathVariable UUID groveId) {
        // TODO: Implement grove suspension
        return groveService.getGrove(groveId)
            .map(grove -> ResponseEntity.ok(GroveResponse.fromModel(grove)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{groveId}/actions/start")
    public ResponseEntity<GroveResponse> startGrove(@PathVariable UUID groveId) {
        // TODO: Implement grove resumption
        return groveService.getGrove(groveId)
            .map(grove -> ResponseEntity.ok(GroveResponse.fromModel(grove)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resolves the SSH private key path from the system property
     * {@code orchard.ssh.key-path}, falling back to {@code ~/.ssh/orchard_ed25519}.
     */
    private static String resolveSshKeyPath() {
        String keyPath = System.getProperty("orchard.ssh.key-path");
        if (keyPath != null && !keyPath.isBlank()) {
            return keyPath;
        }
        return "%s/.ssh/orchard_ed25519".formatted(System.getProperty("user.home"));
    }

    /**
     * Resolves the cultivator ID from the auth filter's request attribute first,
     * falling back to the X-Cultivator-Id header for dev/unauthenticated mode.
     */
    private UUID resolveCultivatorId(HttpServletRequest request, UUID headerCultivatorId) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId != null) {
            return cultivatorId;
        }
        return headerCultivatorId;
    }
}
