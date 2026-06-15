package dev.orchard.trellis;

import dev.orchard.nursery.DevcontainerCliConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
    "dev.orchard.trellis",
    "dev.orchard.api",
    "dev.orchard.roots",
    "dev.orchard.greenhouse"
})
@EnableConfigurationProperties({DevcontainerCliConfig.class})
@EntityScan("dev.orchard.roots.entity")
@EnableJpaRepositories("dev.orchard.roots.repository")
public class OrchardApplication {

    public static void main(String[] args) {
        System.out.println("""

             ██████╗ ██████╗  ██████╗██╗  ██╗ █████╗ ██████╗ ██████╗
            ██╔═══██╗██╔══██╗██╔════╝██║  ██║██╔══██╗██╔══██╗██╔══██╗
            ██║   ██║██████╔╝██║     ███████║███████║██████╔╝██║  ██║
            ██║   ██║██╔══██╗██║     ██╔══██║██╔══██║██╔══██╗██║  ██║
            ╚██████╔╝██║  ██║╚██████╗██║  ██║██║  ██║██║  ██║██████╔╝
             ╚═════╝ ╚═╝  ╚═╝ ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝

            Cloud Development Environments - Growing the Future of Code

            """);
        SpringApplication.run(OrchardApplication.class, args);
    }
}
