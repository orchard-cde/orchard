package dev.orchard.harvest;

import dev.orchard.core.model.DevfileYaml;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Jackson mixins binding {@link DevfileYaml}'s components through {@link LenientBinding}. */
public interface DevfileYamlBinding {

    interface Component {
        @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) DevfileYaml.Container container();
    }
}
