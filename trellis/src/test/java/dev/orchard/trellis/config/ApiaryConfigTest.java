package dev.orchard.trellis.config;

import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.core.model.BeeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiaryConfigTest {

    @Test
    void beeKeeperRegistry_registersOpencodeBeeKeeper() {
        ApiaryConfig config = new ApiaryConfig();
        BeeKeeperRegistry registry = config.beeKeeperRegistry(config.opencodeBeeKeeper());

        assertThat(registry.isRegistered(BeeType.OPENCODE)).isTrue();
    }
}
