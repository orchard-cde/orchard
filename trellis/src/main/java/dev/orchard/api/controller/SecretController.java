package dev.orchard.api.controller;

import dev.orchard.api.dto.PutSecretRequest;
import dev.orchard.api.dto.SecretResponse;
import dev.orchard.api.service.SecretService;
import dev.orchard.core.model.Secret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/secrets")
public class SecretController {

    private final SecretService service;

    public SecretController(SecretService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<SecretResponse>> listSecrets(HttpServletRequest request) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<SecretResponse> secrets = service.list(cultivatorId).stream()
            .map(SecretResponse::fromModel)
            .toList();
        return ResponseEntity.ok(secrets);
    }

    @PutMapping("/{name}")
    public ResponseEntity<SecretResponse> putSecret(
            HttpServletRequest request,
            @PathVariable String name,
            @Valid @RequestBody PutSecretRequest putRequest) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Secret.requireStorableName(name);
        SecretService.Stored stored = service.put(
            cultivatorId, name, putRequest.value().getBytes(StandardCharsets.UTF_8));
        HttpStatus status = stored.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(SecretResponse.fromModel(stored.secret()));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteSecret(
            HttpServletRequest request,
            @PathVariable String name) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        requireValidName(name);
        boolean deleted = service.delete(cultivatorId, name);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static void requireValidName(String name) {
        if (!Secret.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid secret name: " + name);
        }
    }
}
