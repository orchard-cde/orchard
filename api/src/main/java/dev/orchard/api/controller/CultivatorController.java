package dev.orchard.api.controller;

import dev.orchard.api.dto.CultivatorResponse;
import dev.orchard.api.service.CultivatorService;
import dev.orchard.core.model.Cultivator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for cultivator (user) operations.
 * Provides the /api/me endpoint for retrieving the currently authenticated cultivator.
 */
@RestController
@RequestMapping("/api")
public class CultivatorController {

    private final CultivatorService cultivatorService;

    public CultivatorController(CultivatorService cultivatorService) {
        this.cultivatorService = cultivatorService;
    }

    /**
     * Returns the currently authenticated cultivator's profile.
     * The cultivator ID is resolved from the JWT auth filter (request attribute)
     * or from the X-Cultivator-Id header as a fallback in dev mode.
     */
    @GetMapping("/me")
    public ResponseEntity<CultivatorResponse> getCurrentCultivator(
            HttpServletRequest request,
            @RequestHeader(value = "X-Cultivator-Id", required = false) UUID headerCultivatorId) {

        // Prefer auth-filter-resolved cultivator ID, fall back to header
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            cultivatorId = headerCultivatorId;
        }

        if (cultivatorId == null) {
            return ResponseEntity.status(401).build();
        }

        Optional<Cultivator> cultivator = cultivatorService.findById(cultivatorId);
        return cultivator
            .map(c -> ResponseEntity.ok(CultivatorResponse.fromModel(c)))
            .orElse(ResponseEntity.notFound().build());
    }
}
