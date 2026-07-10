package dev.orchard.api.controller;

import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.nursery.SeedlingProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ProviderRegistry providerRegistry;
    private final BuildProperties buildProperties;

    public HealthController(ProviderRegistry providerRegistry, ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.providerRegistry = providerRegistry;
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        var version = buildProperties != null ? buildProperties.getVersion() : "unknown";
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "name", "Orchard",
            "version", version
        ));
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        SeedlingProvider seedlingProvider = providerRegistry.getDefault();
        boolean providerReady = seedlingProvider.isAvailable();

        if (providerReady) {
            return ResponseEntity.ok(Map.of(
                "status", "ready",
                "seedlingProvider", Map.of(
                    "id", seedlingProvider.getProviderId(),
                    "available", true
                )
            ));
        } else {
            return ResponseEntity.status(503).body(Map.of(
                "status", "not_ready",
                "seedlingProvider", Map.of(
                    "id", seedlingProvider.getProviderId(),
                    "available", false
                )
            ));
        }
    }
}
