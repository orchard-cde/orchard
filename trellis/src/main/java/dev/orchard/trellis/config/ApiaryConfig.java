package dev.orchard.trellis.config;

import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.apiary.adapter.OpencodeBeeKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiaryConfig {

    private static final Logger log = LoggerFactory.getLogger(ApiaryConfig.class);

    @Bean
    public BeeKeeperRegistry beeKeeperRegistry(OpencodeBeeKeeper opencodeBeeKeeper) {
        BeeKeeperRegistry registry = new BeeKeeperRegistry();
        registry.register(opencodeBeeKeeper);
        log.info("Registered bee keeper: {}", opencodeBeeKeeper.getBeeType());
        return registry;
    }

    @Bean
    public OpencodeBeeKeeper opencodeBeeKeeper() {
        return new OpencodeBeeKeeper();
    }
}
