package dev.orchard.apiary;

import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeType;
import dev.orchard.core.model.BeeSpec;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Extension point for managing Bees (devcontainer runtimes).
 * Implementations handle lifecycle operations for a specific {@link BeeType}.
 */
public interface BeeKeeper {

    BeeType getBeeType();

    CompletableFuture<Bee> install(Bee bee, BeeSpec spec);

    CompletableFuture<Bee> release(Bee bee);

    CompletableFuture<Bee> smoke(Bee bee);

    CompletableFuture<BeeHealth> inspect(Bee bee);

    Map<String, String> prerequisites();
}
