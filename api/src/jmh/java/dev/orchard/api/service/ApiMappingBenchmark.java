package dev.orchard.api.service;

import dev.orchard.api.dto.GroveResponse;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.GroveState;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ApiMappingBenchmark {

    private String rawStatus;
    private Grove grove;

    @Setup
    public void setUp() {
        rawStatus = "status: done";
        grove = Grove.plant(
                UUID.randomUUID(),
                "bench-grove",
                "https://github.com/orchard-cde/orchard.git",
                "main")
            .withState(GroveState.FLOURISHING);
    }

    @Benchmark
    public void classify(Blackhole bh) {
        bh.consume(GroveService.classifyCloudInitStatus(rawStatus));
    }

    @Benchmark
    public GroveResponse mapGroveResponse() {
        return GroveResponse.fromModel(grove);
    }
}
