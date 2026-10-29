package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerJson;
import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.LifecycleCommand;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;

/** Jackson mixins binding {@link DevcontainerJson}'s components through {@link LenientBinding}. */
public interface DevcontainerJsonBinding {

    @JsonDeserialize(using = LenientBinding.StringOrList.class) List<String> dockerComposeFile();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> runServices();
    @JsonDeserialize(contentUsing = LenientBinding.ObjectOnly.class) Map<String, Map<String, Object>> features();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> overrideFeatureInstallOrder();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> forwardPorts();
    @JsonDeserialize(using = LenientBinding.Mounts.class) List<String> mounts();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> runArgs();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> capAdd();
    @JsonDeserialize(using = LenientBinding.StringList.class) List<String> securityOpt();
    @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) DevcontainerSeed.PortAttributes otherPortsAttributes();
    @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) DevcontainerJson.HostRequirements hostRequirements();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand initializeCommand();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand onCreateCommand();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand updateContentCommand();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postCreateCommand();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postStartCommand();
    @JsonDeserialize(using = LenientBinding.Lifecycle.class) LifecycleCommand postAttachCommand();

    interface Build {
        @JsonDeserialize(using = LenientBinding.StringOrList.class) List<String> cacheFrom();
        @JsonDeserialize(using = LenientBinding.StringList.class) List<String> options();
    }

    interface Customizations {
        @JsonDeserialize(using = LenientBinding.NullAsEmptyRecord.class) DevcontainerJson.VsCode vscode();
    }

    interface VsCode {
        @JsonDeserialize(using = LenientBinding.StringList.class) List<String> extensions();
        @JsonDeserialize(using = LenientBinding.Settings.class) Map<String, Object> settings();
    }
}
