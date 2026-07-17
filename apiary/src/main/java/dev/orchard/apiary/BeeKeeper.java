package dev.orchard.apiary;

import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeType;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.nursery.CommandRunner;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Extension point managing Bees (devcontainer runtimes).
 * Implementations handle lifecycle operations specific to a {@link BeeType}.
 */
public interface BeeKeeper {

    BeeType getBeeType();

    CompletableFuture<Bee> install(Bee bee, BeeSpec spec, CommandRunner runner);

    CompletableFuture<Bee> release(Bee bee, CommandRunner runner);

    CompletableFuture<Bee> smoke(Bee bee, CommandRunner runner);

    CompletableFuture<BeeHealth> inspect(Bee bee, CommandRunner runner);

    Map<String, String> prerequisites();
}
