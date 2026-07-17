package dev.orchard.api.service;

import dev.orchard.core.model.BeeType;

import java.util.Map;
import java.util.UUID;

public interface CredentialResolver {
    Map<String, String> resolve(UUID cultivatorId, BeeType beeType);
}
