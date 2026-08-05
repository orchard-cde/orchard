package dev.orchard.api.controller;

import dev.orchard.api.dto.CreateSshPublicKeyRequest;
import dev.orchard.api.dto.SshPublicKeyResponse;
import dev.orchard.api.service.SshPublicKeyService;
import dev.orchard.core.model.SshPublicKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ssh-keys")
public class SshPublicKeyController {

    private final SshPublicKeyService service;

    public SshPublicKeyController(SshPublicKeyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SshPublicKeyResponse> registerKey(
            HttpServletRequest request,
            @Valid @RequestBody CreateSshPublicKeyRequest createRequest) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SshPublicKey key = service.register(cultivatorId, createRequest.name(), createRequest.publicKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(SshPublicKeyResponse.fromModel(key));
    }

    @GetMapping
    public ResponseEntity<List<SshPublicKeyResponse>> listKeys(HttpServletRequest request) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<SshPublicKeyResponse> keys = service.listForCultivator(cultivatorId).stream()
            .map(SshPublicKeyResponse::fromModel)
            .toList();
        return ResponseEntity.ok(keys);
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> deleteKey(
            HttpServletRequest request,
            @PathVariable UUID keyId) {
        UUID cultivatorId = (UUID) request.getAttribute("cultivatorId");
        if (cultivatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean deleted = service.delete(cultivatorId, keyId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
