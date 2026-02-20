package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FruitGrowerTest {

    @Test
    void parsePortOutput_standardTcpMapping() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:8080");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_multipleMappings() {
        String output = "8080/tcp -> 0.0.0.0:8080\n3000/tcp -> 0.0.0.0:3000";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(2);
        assertThat(mappings.get(0)).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
        assertThat(mappings.get(1)).isEqualTo(new Fruit.PortMapping(3000, 3000, "tcp"));
    }

    @Test
    void parsePortOutput_skipsIpv6Lines() {
        String output = "8080/tcp -> 0.0.0.0:8080\n8080/tcp -> [::]:8080";

        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput(output);

        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst()).isEqualTo(new Fruit.PortMapping(8080, 8080, "tcp"));
    }

    @Test
    void parsePortOutput_differentHostPort() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("8080/tcp -> 0.0.0.0:49152");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(8080, 49152, "tcp"));
    }

    @Test
    void parsePortOutput_emptyOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_noArrowOutput() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("no ports");
        assertThat(mappings).isEmpty();
    }

    @Test
    void parsePortOutput_udpProtocol() {
        List<Fruit.PortMapping> mappings = FruitGrower.parsePortOutput("53/udp -> 0.0.0.0:53");

        assertThat(mappings).containsExactly(new Fruit.PortMapping(53, 53, "udp"));
    }
}
