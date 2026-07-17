package dev.orchard.apiary;

import dev.orchard.core.model.BeeType;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class BeeKeeperRegistry {

    private final ConcurrentHashMap<String, BeeKeeper> keepers = new ConcurrentHashMap<>();

    public void register(BeeKeeper keeper) {
        keepers.put(keeper.getBeeType().name(), keeper);
    }

    public Optional<BeeKeeper> get(BeeType type) {
        return Optional.ofNullable(keepers.get(type.name()));
    }

    public boolean isRegistered(BeeType type) {
        return keepers.containsKey(type.name());
    }
}
