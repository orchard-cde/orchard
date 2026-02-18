package dev.orchard.core.model;

import java.util.List;
import java.util.Map;

/**
 * A Seed is the specification for growing Fruit (a devcontainer.json).
 * It contains all the genetic information needed to produce a container.
 */
public record Seed(
    String name,
    String image,
    String dockerfilePath,
    String dockerComposeFile,
    Map<String, String> buildArgs,
    List<String> features,
    List<String> forwardPorts,
    Map<String, String> containerEnv,
    List<String> postCreateCommands,
    List<String> postStartCommands,
    VsCodeCustomizations vscodeCustomizations
) {
    public record VsCodeCustomizations(
        List<String> extensions,
        Map<String, Object> settings
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String image;
        private String dockerfilePath;
        private String dockerComposeFile;
        private Map<String, String> buildArgs = Map.of();
        private List<String> features = List.of();
        private List<String> forwardPorts = List.of();
        private Map<String, String> containerEnv = Map.of();
        private List<String> postCreateCommands = List.of();
        private List<String> postStartCommands = List.of();
        private VsCodeCustomizations vscodeCustomizations;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder image(String image) {
            this.image = image;
            return this;
        }

        public Builder dockerfilePath(String dockerfilePath) {
            this.dockerfilePath = dockerfilePath;
            return this;
        }

        public Builder dockerComposeFile(String dockerComposeFile) {
            this.dockerComposeFile = dockerComposeFile;
            return this;
        }

        public Builder buildArgs(Map<String, String> buildArgs) {
            this.buildArgs = buildArgs;
            return this;
        }

        public Builder features(List<String> features) {
            this.features = features;
            return this;
        }

        public Builder forwardPorts(List<String> forwardPorts) {
            this.forwardPorts = forwardPorts;
            return this;
        }

        public Builder containerEnv(Map<String, String> containerEnv) {
            this.containerEnv = containerEnv;
            return this;
        }

        public Builder postCreateCommands(List<String> postCreateCommands) {
            this.postCreateCommands = postCreateCommands;
            return this;
        }

        public Builder postStartCommands(List<String> postStartCommands) {
            this.postStartCommands = postStartCommands;
            return this;
        }

        public Builder vscodeCustomizations(VsCodeCustomizations vscodeCustomizations) {
            this.vscodeCustomizations = vscodeCustomizations;
            return this;
        }

        public Seed build() {
            return new Seed(name, image, dockerfilePath, dockerComposeFile, buildArgs,
                features, forwardPorts, containerEnv, postCreateCommands, postStartCommands,
                vscodeCustomizations);
        }
    }
}
