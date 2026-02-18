package dev.orchard.api.controller;

import dev.orchard.api.dto.CreateGroveRequest;
import dev.orchard.api.dto.GroveResponse;
import dev.orchard.api.service.GroveService;
import dev.orchard.core.model.Grove;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
            @RequestHeader("X-Cultivator-Id") UUID cultivatorId,
            @Valid @RequestBody CreateGroveRequest request) {
        Grove grove = groveService.plantGrove(cultivatorId, request);
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
            @RequestHeader("X-Cultivator-Id") UUID cultivatorId) {
        List<GroveResponse> groves = groveService.getGrovesForCultivator(cultivatorId)
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
}
