package dev.orchard.trellis.config;

import dev.orchard.nursery.qemu.QemuConfig;
import dev.orchard.nursery.qemu.QemuEnvironmentInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orchard.nursery.provider", havingValue = "qemu", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QemuStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QemuStartupRunner.class);

    private final QemuConfig qemuConfig;

    public QemuStartupRunner(QemuConfig qemuConfig) {
        this.qemuConfig = qemuConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running QEMU environment initialization...");
        QemuEnvironmentInitializer initializer = new QemuEnvironmentInitializer();
        initializer.initialize(qemuConfig);
    }
}
