package dev.orchard.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * A devcontainer lifecycle hook. The {@code @type} discriminator lets the sealed
 * hierarchy round-trip through JSON; without it a command serializes as a bare
 * record and fails to deserialize against this abstract type.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LifecycleCommand.Sequential.class, name = "sequential"),
    @JsonSubTypes.Type(value = LifecycleCommand.Parallel.class, name = "parallel")
})
public sealed interface LifecycleCommand permits LifecycleCommand.Sequential, LifecycleCommand.Parallel {
    record Sequential(List<String> args) implements LifecycleCommand {}
    record Parallel(Map<String, List<String>> steps) implements LifecycleCommand {}
}