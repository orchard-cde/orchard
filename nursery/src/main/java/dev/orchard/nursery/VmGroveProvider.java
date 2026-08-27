package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Seedling;
import dev.orchard.vine.SshVine;
import dev.orchard.vine.Vine;

import java.util.concurrent.CompletableFuture;

/**
 * {@link GroveProvider} for VM-backed groves. A pure adapter: every method forwards to the
 * unchanged {@link SeedlingProvider} / {@link FruitGrower} pair, so this class introduces no
 * behaviour of its own and is the reason stage 1 is safe.
 */
public final class VmGroveProvider implements GroveProvider {

    private final SeedlingProvider seedlingProvider;
    private final FruitGrower fruitGrower;

    public VmGroveProvider(SeedlingProvider seedlingProvider,
                           FruitGrower fruitGrower) {
        this.seedlingProvider = seedlingProvider;
        this.fruitGrower = fruitGrower;
    }

    @Override public String getProviderId() { return seedlingProvider.getProviderId(); }

    @Override public boolean isAvailable() { return seedlingProvider.isAvailable(); }

    @Override public CompletableFuture<Seedling> plantSubstrate(Seedling s) { return seedlingProvider.plant(s); }

    @Override public CompletableFuture<Seedling> water(Seedling s) { return seedlingProvider.water(s); }

    @Override public CompletableFuture<Seedling> dormant(Seedling s) { return seedlingProvider.dormant(s); }

    @Override public CompletableFuture<Void> uproot(Seedling s) { return seedlingProvider.uproot(s); }

    @Override public CompletableFuture<Seedling> inspect(Seedling s) { return seedlingProvider.inspect(s); }

    @Override public CompletableFuture<Fruit> growFruit(Seedling s, Fruit f) { return fruitGrower.grow(s, f); }

    @Override public CompletableFuture<Void> compostFruit(Seedling s, Fruit f) { return fruitGrower.compost(s, f); }

    @Override
    public Vine vine(Seedling seedling) {
        return new SshVine(seedling);
    }

    @Override
    public void verifyDevcontainerCli(Seedling seedling, String expectedVersion) {
        SeedlingProvider.verifyDevcontainerCli(seedling, expectedVersion, vine(seedling).commands());
    }
}
