package dev.orchard.nursery;

import dev.orchard.core.model.Fruit;
import dev.orchard.core.model.VmPlot;
import dev.orchard.nursery.azure.AzureVmGroveProvider;
import dev.orchard.nursery.gcp.GoogleComputeGroveProvider;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
        Fruit fruit = TestPlots.budded(plot);

        assertUnimplemented(() -> provider.growFruit(plot, fruit), expected);
        assertUnimplemented(() -> provider.compostFruit(plot, fruit), expected);
        assertUnimplemented(() -> provider.vine(plot), expected);
        assertUnimplemented(() -> provider.verifyDevcontainerCli(plot, "0.87.0"), expected);
    }

    private static void assertUnimplemented(ThrowingCallable call, String expected) {
        assertThatThrownBy(call).isInstanceOf(UnsupportedOperationException.class).hasMessage(expected);
    }
}
