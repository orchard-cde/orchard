package dev.orchard.nursery;

/**
 * Where a freshly launched seedling lives and what the provider calls it. Identity and
 * reachability are resolved from the same substrate response, so they travel together.
 */
public record PlantedSeedling(String providerInstanceId, String host, int sshPort) {}
