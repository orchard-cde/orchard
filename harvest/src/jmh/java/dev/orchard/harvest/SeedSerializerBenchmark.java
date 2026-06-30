package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import dev.orchard.core.model.Seed;
import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SeedSerializerBenchmark {

    private SeedSerializer serializer;
    private Seed seed;
    private String json;

    @Setup
    public void setUp() {
        serializer = new SeedSerializer();
        seed = DevcontainerSeed.builder()
            .name("bench")
            .image("mcr.microsoft.com/devcontainers/java:21")
            .forwardPorts(List.of("8080", "5432"))
            .containerEnv(Map.of("BENCH", "true"))
            .features(Map.of(
                "ghcr.io/devcontainers/features/docker-in-docker:2", Map.of("version", "latest"),
                "ghcr.io/devcontainers/features/node:1", Map.of()))
            .build();
        json = serializer.serialize(seed);
    }

    @Benchmark
    public String serialize() {
        return serializer.serialize(seed);
    }

    @Benchmark
    public Seed deserialize() {
        return serializer.deserialize(json);
    }
}
