package dev.orchard.api.controller;

import dev.orchard.api.dto.PrebuildResponse;
import dev.orchard.api.dto.TriggerPrebuildRequest;
import dev.orchard.core.model.Prebuild;
import dev.orchard.greenhouse.PrebuildService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing workspace prebuilds.
 * Prebuilds allow pre-caching container images for repositories so that
 * new Groves can start faster by using an already-built image.
 */
@RestController
@RequestMapping("/api/prebuilds")
public class PrebuildController {

    private final PrebuildService prebuildService;

    public PrebuildController(PrebuildService prebuildService) {
        this.prebuildService = prebuildService;
    }

    /**
     * Triggers a new prebuild for the given repository and branch.
     * The build runs asynchronously; poll GET /api/prebuilds/{id} for status.
     */
    @PostMapping
    public ResponseEntity<PrebuildResponse> triggerPrebuild(
            @Valid @RequestBody TriggerPrebuildRequest request) {
        Prebuild prebuild = prebuildService.triggerPrebuild(
            request.repositoryUrl(), request.branch());
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(PrebuildResponse.fromModel(prebuild));
    }

    /**
     * Lists all prebuilds, ordered by creation time (newest first).
     */
    @GetMapping
    public ResponseEntity<List<PrebuildResponse>> listPrebuilds() {
        List<PrebuildResponse> prebuilds = prebuildService.listPrebuilds().stream()
            .map(PrebuildResponse::fromModel)
            .toList();
        return ResponseEntity.ok(prebuilds);
    }

    /**
     * Gets the current status of a specific prebuild.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PrebuildResponse> getPrebuild(@PathVariable UUID id) {
        return prebuildService.getPrebuild(id)
            .map(prebuild -> ResponseEntity.ok(PrebuildResponse.fromModel(prebuild)))
            .orElse(ResponseEntity.notFound().build());
    }
}
