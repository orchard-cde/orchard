package dev.orchard.canopy;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
    "dev.orchard.canopy",
    "dev.orchard.api",
    "dev.orchard.roots"
})
@EntityScan("dev.orchard.roots.entity")
@EnableJpaRepositories("dev.orchard.roots.repository")
@Push
public class CanopyApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        System.out.println("""

             ██████╗ █████╗ ███╗   ██╗ ██████╗ ██████╗ ██╗   ██╗
            ██╔════╝██╔══██╗████╗  ██║██╔═══██╗██╔══██╗╚██╗ ██╔╝
            ██║     ███████║██╔██╗ ██║██║   ██║██████╔╝ ╚████╔╝
            ██║     ██╔══██║██║╚██╗██║██║   ██║██╔═══╝   ╚██╔╝
            ╚██████╗██║  ██║██║ ╚████║╚██████╔╝██║        ██║
             ╚═════╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝        ╚═╝

            Orchard Canopy - See the Forest Through the Trees

            """);
        SpringApplication.run(CanopyApplication.class, args);
    }
}
