package dev.orchard.core.model;

import java.util.List;

/**
 * The wire shape of a devfile 2.x — only the fields Orchard maps.
 * Spec: https://devfile.io/docs/2.2.0/devfile-schema
 */
public record DevfileYaml(
    String schemaVersion,
    Metadata metadata,
    List<Component> components,
    List<Command> commands,
    Events events
) {

    public record Metadata(String name) {}

    /** Only {@code container} components are mapped; other kinds bind with a null container. */
    public record Component(String name, Container container) {}

    public record Container(
        String image,
        List<Env> env,
        List<Endpoint> endpoints,
        String memoryLimit,
        String memoryRequest,
        String cpuLimit,
        String cpuRequest
    ) {}

    public record Env(String name, String value) {}

    public record Endpoint(Integer targetPort) {}

    /** Only {@code exec} commands are mapped; {@code apply}/{@code composite} bind with a null exec. */
    public record Command(String id, Exec exec) {}

    public record Exec(String commandLine) {}

    public record Events(List<String> preStart, List<String> postStart) {}
}
