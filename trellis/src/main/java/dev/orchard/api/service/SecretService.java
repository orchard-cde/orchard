package dev.orchard.api.service;

import dev.orchard.api.dto.SecretWarning;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Secret;
import dev.orchard.core.model.Seed;
import dev.orchard.knothole.SecretVault;
import dev.orchard.roots.entity.SecretEntity;
import dev.orchard.roots.repository.SecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SecretService {

    private static final Logger log = LoggerFactory.getLogger(SecretService.class);

    private final SecretRepository repository;
    private final SecretVault vault;
    private final CultivatorService cultivatorService;
    private final TransactionTemplate tx;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public SecretService(SecretRepository repository, SecretVault vault,
                         CultivatorService cultivatorService, TransactionTemplate tx) {
        this.repository = repository;
        this.vault = vault;
        this.cultivatorService = cultivatorService;
        this.tx = tx;
    }

    public record Stored(Secret secret, boolean created) {}

    private record Written(Secret secret, boolean created, String oldKey) {}

    public Stored put(UUID cultivatorId, String name, byte[] value) {
        Secret.requireStorableName(name);
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (value.length > vault.maxValueBytes()) {
            throw new IllegalArgumentException("value must not exceed " + vault.maxValueBytes() + " bytes");
        }
        for (byte b : value) {
            if (b == 0) {
                throw new IllegalArgumentException("value must not contain NUL");
            }
        }

        String newKey = UUID.randomUUID().toString();
        inFlight.add(newKey);
        Written written;
        try {
            vault.put(newKey, value);
            try {
                written = commit(cultivatorId, name, newKey);
            } catch (DataIntegrityViolationException first) {
                written = commit(cultivatorId, name, newKey);
            }
        } catch (RuntimeException e) {
            inFlight.remove(newKey);
            deleteOrSweep(newKey, "after failed write");
            throw e;
        } finally {
            inFlight.remove(newKey);
        }

        if (written.oldKey() != null) {
            deleteOrSweep(written.oldKey(), "after overwrite");
        }
        log.info("Stored secret {} (created={})", name, written.created());
        return new Stored(written.secret(), written.created());
    }

    private Written commit(UUID cultivatorId, String name, String newKey) {
        return tx.execute(status -> {
            cultivatorService.ensureCultivator(cultivatorId);
            var existing = repository.findByCultivatorIdAndName(cultivatorId, name);
            Secret current = existing.map(SecretEntity::toModel).orElse(null);
            Secret updated = current == null
                ? Secret.store(cultivatorId, name, newKey)
                : current.withValueWritten(newKey, Instant.now());
            String oldKey = current == null ? null : current.vaultKey();
            repository.saveAndFlush(SecretEntity.fromModel(updated));
            return new Written(updated, current == null, oldKey);
        });
    }

    private void deleteOrSweep(String key, String context) {
        try {
            vault.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete vault entry {} {}; sweeping: {}", key, context, e.toString());
            sweepQuietly();
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Sweep failed: {}", e.getClass().getName());
        }
    }

    public List<Secret> list(UUID cultivatorId) {
        return repository.findByCultivatorId(cultivatorId).stream()
            .map(SecretEntity::toModel)
            .toList();
    }

    public boolean delete(UUID cultivatorId, String name) {
        String key = tx.execute(status -> repository.findByCultivatorIdAndName(cultivatorId, name)
            .map(entity -> {
                repository.delete(entity);
                return entity.getVaultKey();
            })
            .orElse(null));
        if (key == null) {
            return false;
        }
        deleteOrSweep(key, "after delete");
        log.info("Deleted secret {}", name);
        return true;
    }

    public Map<String, byte[]> resolve(UUID cultivatorId, Collection<String> names) {
        Map<String, byte[]> resolved = new HashMap<>();
        for (SecretEntity entity : repository.findByCultivatorIdAndNameIn(cultivatorId, names)) {
            byte[] value = vault.get(entity.getVaultKey()).orElseThrow(() ->
                new IllegalStateException("Vault entry missing for secret " + entity.getName()));
            resolved.put(entity.getName(), value);
        }
        return resolved;
    }

    public List<SecretWarning> missingFor(UUID cultivatorId, Seed seed) {
        if (!(seed instanceof DevcontainerSeed devcontainer) || devcontainer.secrets() == null) {
            return List.of();
        }
        Set<String> present = repository.findByCultivatorId(cultivatorId).stream()
            .map(SecretEntity::getName)
            .collect(Collectors.toSet());
        List<SecretWarning> warnings = new ArrayList<>();
        devcontainer.secrets().forEach((name, declaration) -> {
            if (Secret.isStorableName(name) && !present.contains(name)) {
                warnings.add(new SecretWarning(name,
                    declaration == null ? null : declaration.description(),
                    declaration == null ? null : declaration.documentationUrl()));
            }
        });
        return warnings;
    }

    public int sweep() {
        Set<String> orphans = new HashSet<>(vault.keys());
        Set<String> inFlightSnapshot = Set.copyOf(inFlight);
        Set<String> committed = new HashSet<>(repository.findAllVaultKeys());
        orphans.removeAll(inFlightSnapshot);
        orphans.removeAll(committed);
        int removed = 0;
        for (String key : orphans) {
            try {
                if (vault.delete(key)) {
                    removed++;
                }
            } catch (RuntimeException e) {
                log.warn("Sweep could not delete vault entry {}", key);
            }
        }
        return removed + vault.purge();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int removed = sweep();
        log.info("Knothole sweep removed {} orphaned artifacts", removed);
    }
}
