package dev.orchard.nursery;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.azure.AzureVmGroveProvider;
import dev.orchard.nursery.gcp.GoogleComputeGroveProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnimplementedVmProvidersTest {

    static Stream<Arguments> providers() {
        return Stream.of(
            Arguments.of(new AzureVmGroveProvider(null, new FruitGrower()),
                "Azure VM provider not yet implemented"),
            Arguments.of(new GoogleComputeGroveProvider(null, new FruitGrower()),
                "GCP Compute provider not yet implemented"));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void vmOperations_throwNotYetImplemented(GroveProvider provider, String expected) {
        VmPlot plot = TestPlots.fake();
        Fruit fruit = Fruit.bud(plot.groveId(), plot.id(),
            DevcontainerSeed.builder().name("test").image("alpine:3").build());

        assertThatThrownBy(() -> provider.growFruit(plot, fruit))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage(expected);
        assertThatThrownBy(() -> provider.compostFruit(plot, fruit))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage(expected);
        assertThatThrownBy(() -> provider.vine(plot))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage(expected);
        assertThatThrownBy(() -> provider.verifyDevcontainerCli(plot, "0.87.0"))
            .isInstanceOf(UnsupportedOperationException.class).hasMessage(expected);
    }
}
