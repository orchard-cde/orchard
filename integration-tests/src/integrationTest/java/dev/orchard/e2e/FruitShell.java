package dev.orchard.e2e;

import dev.orchard.api.service.GroveService;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.Plot;
import dev.orchard.nursery.GroveProvider;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.vine.ShellQuote;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Commands inside a grove's primary fruit, as its remote user, reached the way trellis reaches it. */
record FruitShell(GroveProvider provider, Plot plot, String containerId, String remoteUser) {

    static final String FRUIT_SECRETS_PATH = "/dev/shm/orchard/secrets.env";

    static FruitShell of(GroveService groveService, ProviderRegistry providers, UUID groveId) {
        Grove grove = groveService.getGrove(groveId).orElseThrow();
        Fruit fruit = grove.fruits().getFirst();
        assertThat(fruit.remoteUser()).as("fruit remote user").isNotBlank();
        return new FruitShell(providers.require(grove.plot().providerId()), grove.plot(), fruit.containerId(),
            fruit.remoteUser());
    }

    String run(String command) throws Exception {
        return GroveClient.runInFruit(provider, plot, containerId, remoteUser, command);
    }

    String loginEnv(String name) throws Exception {
        return run("sh -lc 'printf %s \"$" + name + "\"'");
    }

    void assertSecretDelivered(String name, String value, String step) throws Exception {
        assertThat(loginEnv(name)).as("%s: fruit login shell sees %s", step, name).isEqualTo(value);
        String group = run("id -gn");
        assertThat(run("stat -c '%U %G %a' " + FRUIT_SECRETS_PATH)).as("%s: fruit secrets file ownership", step)
            .isEqualTo("root " + group + " 440");
        String diff = provider.host(plot).execute(
            provider.runtime(plot).cli() + " diff " + ShellQuote.quote(containerId));
        assertThat(diff).as("%s: container layer free of the secrets file", step).doesNotContain("secrets.env");
    }
}
