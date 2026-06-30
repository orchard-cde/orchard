package dev.orchard.nursery;

import org.openjdk.jmh.annotations.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CloudInitTemplateBenchmark {

    private Map<String, String> vars;

    @Setup
    public void setUp() {
        vars = Map.of(
            "ssh_authorized_keys_block",
            "    ssh_authorized_keys:\n      - ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBench bench@orchard\n",
            "cli_version", "0.80.0");
    }

    @Benchmark
    public String render() {
        return CloudInitTemplate.render("/cloud-init/qemu.yaml.tpl", vars);
    }
}
