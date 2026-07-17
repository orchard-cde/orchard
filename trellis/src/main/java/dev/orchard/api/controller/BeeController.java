package dev.orchard.api.controller;

import dev.orchard.api.dto.BeeResponse;
import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.service.BeeService;
import dev.orchard.core.model.Bee;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/groves/{groveId}/bees")
public class BeeController {

    private final BeeService beeService;

    public BeeController(BeeService beeService) {
        this.beeService = beeService;
    }

    @PostMapping
    public ResponseEntity<BeeResponse> createBee(
            @PathVariable UUID groveId,
            @Valid @RequestBody CreateBeeRequest request) {
        UUID cultivatorId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        Bee bee = beeService.attachBee(groveId, cultivatorId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BeeResponse.fromModel(bee));
    }

    @GetMapping
    public ResponseEntity<List<BeeResponse>> listBees(@PathVariable UUID groveId) {
        List<BeeResponse> bees = beeService.listBees(groveId).stream()
            .map(BeeResponse::fromModel)
            .toList();
        return ResponseEntity.ok(bees);
    }

    @GetMapping("/{beeId}")
    public ResponseEntity<BeeResponse> getBee(
            @PathVariable UUID groveId,
            @PathVariable UUID beeId) {
        return beeService.getBee(beeId)
            .map(bee -> ResponseEntity.ok(BeeResponse.fromModel(bee)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{beeId}/wake")
    public ResponseEntity<BeeResponse> wakeBee(
            @PathVariable UUID groveId,
            @PathVariable UUID beeId) {
        return beeService.wake(beeId)
            .map(bee -> ResponseEntity.ok(BeeResponse.fromModel(bee)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{beeId}/smoke")
    public ResponseEntity<BeeResponse> smokeBee(
            @PathVariable UUID groveId,
            @PathVariable UUID beeId) {
        return beeService.smoke(beeId)
            .map(bee -> ResponseEntity.ok(BeeResponse.fromModel(bee)))
            .orElse(ResponseEntity.notFound().build());
    }
}
