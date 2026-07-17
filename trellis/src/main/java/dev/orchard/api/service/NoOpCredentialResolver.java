package dev.orchard.api.service;

import dev.orchard.core.model.BeeType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class NoOpCredentialResolver implements CredentialResolver {

    @Override
    public Map<String, String> resolve(UUID cultivatorId, BeeType beeType) {
        return Map.of();
    }
}
