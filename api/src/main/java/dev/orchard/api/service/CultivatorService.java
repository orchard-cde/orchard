package dev.orchard.api.service;

import dev.orchard.core.model.Cultivator;
import dev.orchard.roots.entity.CultivatorEntity;
import dev.orchard.roots.repository.CultivatorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing Cultivators (users) in the Orchard.
 * Handles OAuth-based cultivator lookup and creation (upsert).
 */
@Service
public class CultivatorService {

    private static final Logger log = LoggerFactory.getLogger(CultivatorService.class);

    private final CultivatorRepository cultivatorRepository;

    public CultivatorService(CultivatorRepository cultivatorRepository) {
        this.cultivatorRepository = cultivatorRepository;
    }

    /**
     * Find an existing cultivator by OAuth provider and provider ID, or create a new one.
     * If the cultivator already exists, their profile fields (email, avatarUrl, displayName)
     * are updated and lastActiveAt is refreshed.
     */
    @Transactional
    public Cultivator findOrCreateCultivator(String provider, String providerId,
                                              String username, String email,
                                              String avatarUrl, String displayName) {
        Optional<CultivatorEntity> existing = cultivatorRepository.findByProviderAndProviderId(provider, providerId);

        if (existing.isPresent()) {
            CultivatorEntity entity = existing.get();
            entity.setLastActiveAt(Instant.now());

            // Update mutable profile fields if they changed
            if (email != null && !email.equals(entity.getEmail())) {
                entity.setEmail(email);
            }
            if (avatarUrl != null && !avatarUrl.equals(entity.getAvatarUrl())) {
                entity.setAvatarUrl(avatarUrl);
            }
            if (displayName != null && !displayName.equals(entity.getDisplayName())) {
                entity.setDisplayName(displayName);
            }

            cultivatorRepository.save(entity);
            log.debug("Updated existing cultivator {} for provider {}/{}", entity.getId(), provider, providerId);
            return entity.toModel();
        }

        // Create new cultivator from OAuth info
        Cultivator cultivator = Cultivator.createFromOAuth(provider, providerId, username, email, avatarUrl, displayName);
        CultivatorEntity entity = CultivatorEntity.fromModel(cultivator);
        cultivatorRepository.save(entity);
        log.info("Created new cultivator {} for provider {}/{}", cultivator.id(), provider, providerId);
        return cultivator;
    }

    /**
     * Find a cultivator by their ID.
     */
    public Optional<Cultivator> findById(UUID cultivatorId) {
        return cultivatorRepository.findById(cultivatorId)
            .map(CultivatorEntity::toModel);
    }

    /**
     * Find a cultivator by username.
     */
    public Optional<Cultivator> findByUsername(String username) {
        return cultivatorRepository.findByUsername(username)
            .map(CultivatorEntity::toModel);
    }
}
