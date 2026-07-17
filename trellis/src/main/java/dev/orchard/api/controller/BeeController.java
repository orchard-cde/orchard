package dev.orchard.api.controller;

import dev.orchard.api.dto.BeeResponse;
import dev.orchard.api.dto.CreateBeeRequest;
import dev.orchard.api.dto.SwarmStatusResponse;
import dev.orchard.api.service.BeeService;
import dev.orchard.core.model.Bee;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groves/{groveId}/bees")
public class BeeController {

    private final BeeService beeService;

    public BeeController(BeeService beeService) {
        this.beeService = beeService;
    }

    @PostMapping
    public ResponseEntity<BeeResponse> createBee(
            HttpServletRequest request,
            @RequestHeader(value = "X-Cultivator-Id", required = false) UUID headerCultivatorId,
            @PathVariable UUID groveId,
            @Valid @RequestBody CreateBeeRequest createBeeRequest) {
        UUID cultivatorId = resolveCultivatorId(request, headerCultivatorId);
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Bee bee = beeService.attachBee(groveId, cultivatorId, createBeeRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BeeResponse.fromModel(bee));
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

    @GetMapping("/status")
    public ResponseEntity<SwarmStatusResponse> swarmStatus(@PathVariable UUID groveId) {
        List<Bee> bees = beeService.listBees(groveId);
        var byState = bees.stream()
            .collect(Collectors.groupingBy(
                bee -> bee.state().name(),
                Collectors.summingInt(bee -> 1)));
        return ResponseEntity.ok(new SwarmStatusResponse(groveId, bees.size(), byState));
    }

    @PostMapping("/{beeId}/actions/wake")
    public ResponseEntity<BeeResponse> wakeBee(
            @PathVariable UUID groveId,
            @PathVariable UUID beeId) {
        return beeService.wake(beeId)
            .map(bee -> ResponseEntity.ok(BeeResponse.fromModel(bee)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{beeId}/actions/smoke")
    public ResponseEntity<BeeResponse> smokeBee(
            @PathVariable UUID groveId,
            @PathVariable UUID beeId) {
        return beeService.smoke(beeId)
            .map(bee -> ResponseEntity.ok(BeeResponse.fromModel(bee)))
            .orElse(ResponseEntity.notFound().build());
    }

    private UUID resolveCultivatorId(HttpServletRequest request, UUID headerCultivatorId) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId != null) {
            return cultivatorId;
        }
        return headerCultivatorId;
    }
}
