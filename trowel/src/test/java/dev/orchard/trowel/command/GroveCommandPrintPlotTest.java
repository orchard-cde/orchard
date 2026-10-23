package dev.orchard.trowel.command;

import dev.orchard.trowel.client.OrchardClient.PlotInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GroveCommandPrintPlotTest {

    private static String print(PlotInfo plot) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            GroveCommand.printPlot(plot);
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    private static PlotInfo vm(String host, Integer port) {
        return new PlotInfo("VM", UUID.randomUUID(), "GERMINATING", "qemu", host, port, 2, 4096, 50, null);
    }

    @Test
    void vmWithNullHost_omitsHostLine() {
        String output = print(vm(null, null));

        assertThat(output).contains("Plot (VM):").doesNotContain("null").doesNotContain("Host:");
        assertThat(output).contains("2 CPU, 4096 MB RAM, 50 GB disk");
    }

    @Test
    void vmWithHostAndPort_printsHostColonPort() {
        assertThat(print(vm("10.0.0.5", 22))).contains("Host:      10.0.0.5:22");
    }

    @Test
    void vmWithHostButNoPort_printsHostAlone() {
        String output = print(vm("10.0.0.5", null));

        assertThat(output).contains("Host:      10.0.0.5").doesNotContain(":null").doesNotContain("10.0.0.5:");
    }

    @Test
    void containerPlot_printsRuntimeAndOnlyNonNullResources() {
        PlotInfo plot = new PlotInfo("CONTAINER", UUID.randomUUID(), "FLOURISHING", "docker",
            null, null, null, 512, null, "docker");

        String output = print(plot);

        assertThat(output).contains("Plot (container):", "Runtime:   docker", "Resources: 512 MB RAM")
            .doesNotContain("null").doesNotContain("Host:");
    }
}
