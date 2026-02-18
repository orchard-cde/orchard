package dev.orchard.canopy.config;

import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.nursery.qemu.QemuConfig;
import dev.orchard.nursery.qemu.QemuSeedlingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CanopyConfig {

    @Bean
    public QemuConfig qemuConfig() {
        return QemuConfig.defaults();
    }

    @Bean
    public SeedlingProvider seedlingProvider(QemuConfig config) {
        return new QemuSeedlingProvider(config);
    }

    @Bean
    public FruitGrower fruitGrower() {
        return new FruitGrower();
    }
}
