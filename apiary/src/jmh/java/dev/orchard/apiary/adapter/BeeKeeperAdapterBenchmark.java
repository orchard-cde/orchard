package dev.orchard.apiary.adapter;

import dev.orchard.apiary.BeeKeeperRegistry;
import dev.orchard.apiary.support.StubCommandRunner;
import dev.orchard.core.model.Bee;
import dev.orchard.core.model.BeeHealth;
import dev.orchard.core.model.BeeSpec;
import dev.orchard.core.model.BeeType;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmarks for the BeeKeeper adapter surface, using an in-memory CommandRunner so the
 * scores reflect orchard's own code rather than SSH.
 *
 * <p>Every BeeKeeper method returns a CompletableFuture from a virtual-thread executor, so the
 * whole-method benchmarks are dominated by dispatch overhead — measured at roughly 22x the
 * config-render signal. {@link #renderConfig()} exists to measure that signal in isolation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BeeKeeperAdapterBenchmark {

    private OpencodeBeeKeeper keeper;
    private StubCommandRunner runner;
    private BeeKeeperRegistry registry;
    private BeeSpec spec;
    private Bee headlessBee;
    private Bee interactiveBee;
    private Bee runningBee;

    @Setup
    public void setUp() {
        keeper = new OpencodeBeeKeeper();
        runner = new StubCommandRunner();
        registry = new BeeKeeperRegistry();
        registry.register(keeper);

        UUID groveId = UUID.randomUUID();
        spec = new BeeSpec(BeeType.OPENCODE, "1.0.0",
            Map.of("mode", "headless", "model", "claude-opus-5", "theme", "dark"));
        headlessBee = Bee.hatching(groveId, spec);

        BeeSpec interactiveSpec = new BeeSpec(BeeType.OPENCODE, "1.0.0",
            Map.of("mode", "interactive"));
        interactiveBee = Bee.hatching(groveId, interactiveSpec);

        runningBee = headlessBee.withProcessId("12345");
    }

    @Benchmark
    public Optional<?> registryGet() {
        return registry.get(BeeType.OPENCODE);
    }

    @Benchmark
    public String renderConfig() throws Exception {
        return keeper.renderConfig(headlessBee, spec);
    }

    @Benchmark
    public Bee install() {
        return keeper.install(headlessBee, spec, runner).join();
    }

    @Benchmark
    public Bee releaseHeadless() {
        return keeper.release(headlessBee, runner).join();
    }

    @Benchmark
    public Bee releaseInteractive() {
        return keeper.release(interactiveBee, runner).join();
    }

    @Benchmark
    public Bee smoke() {
        return keeper.smoke(runningBee, runner).join();
    }

    @Benchmark
    public BeeHealth inspect() {
        return keeper.inspect(runningBee, runner).join();
    }
}
