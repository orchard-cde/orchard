package dev.orchard.trellis.config;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.DevfileSeed;
import dev.orchard.core.model.LifecycleCommand;
import dev.orchard.core.model.Seed;
import dev.orchard.core.model.WaitFor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link Seed} model hierarchy for binding reflection so that persisted
 * {@code seed_json} round-trips through {@code SeedSerializer} in the native image.
 *
 * <p>trellis is native-compiled and deserializes seeds at runtime
 * ({@code FruitEntity.toModel()}). Without these hints, a seed containing a lifecycle
 * command or {@code waitFor} fails to deserialize in the native binary — the failure is
 * swallowed to a null seed and surfaces later as "Fruit has no DevcontainerSeed". The
 * polymorphic subtypes ({@link LifecycleCommand} Sequential/Parallel, the {@link Seed}
 * subtype {@link DevcontainerSeed}) are listed explicitly so {@code @JsonSubTypes}
 * resolution works under native-image.
 *
 * <p>Uses {@code @RegisterReflectionForBinding} (the repo's binding idiom — see
 * {@code GroveEventBroadcaster}) instead of a hand-written {@code reflect-config.json}, so a
 * rename of any model class is tracked by the compiler rather than silently breaking native.
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
    Seed.class,
    DevcontainerSeed.class,
    DevcontainerSeed.VsCodeCustomizations.class,
    DevfileSeed.class,
    LifecycleCommand.class,
    LifecycleCommand.Sequential.class,
    LifecycleCommand.Parallel.class,
    WaitFor.class
})
public class SeedReflectionConfig {
}
