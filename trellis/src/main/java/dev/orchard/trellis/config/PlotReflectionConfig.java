package dev.orchard.trellis.config;

import dev.orchard.core.model.ContainerPlot;
import dev.orchard.core.model.ContainerRuntime;
import dev.orchard.core.model.ContainerSpec;
import dev.orchard.core.model.Plot;
import dev.orchard.core.model.PlotState;
import dev.orchard.core.model.VmPlot;
import dev.orchard.core.model.VmSpec;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link Plot} hierarchy for binding reflection so that persisted {@code plot_json}
 * round-trips through {@code PlotSerializer} in the native image. The sealed subtypes are listed
 * explicitly so {@code @JsonSubTypes} resolution works under native-image.
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
    Plot.class,
    VmPlot.class,
    ContainerPlot.class,
    VmSpec.class,
    ContainerSpec.class,
    ContainerRuntime.class,
    PlotState.class
})
public class PlotReflectionConfig {
}
