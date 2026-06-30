package dev.orchard.harvest;

import dev.orchard.core.model.DevcontainerSeed;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DevcontainerParserBenchmark {

    private DevcontainerParser parser;
    private String small;
    private String large;

    @Setup
    public void setUp() throws IOException {
        parser = new DevcontainerParser();
        small = readFixture("fixtures/small-devcontainer.json");
        large = readFixture("fixtures/large-devcontainer.json");
    }

    private static String readFixture(String path) throws IOException {
        try (InputStream in =
                 DevcontainerParserBenchmark.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("fixture not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Benchmark
    public Optional<DevcontainerSeed> parseSmall() {
        return parser.parseJson(small);
    }

    @Benchmark
    public Optional<DevcontainerSeed> parseLarge() {
        return parser.parseJson(large);
    }
}
