# Unit-level micro-benchmarks

Fast, QEMU-free JMH micro-benchmarks of hot pure-CPU paths, co-located in the
modules that own them. Complements the end-to-end suite in `orchard-gauge`.

## Run

```bash
# all modules
./gradlew :harvest:jmh :nursery:jmh :api:jmh

# one module
./gradlew :harvest:jmh
```

Results are written as JSON to each module's `build/results/jmh/results.json`
(gitignored). Run config (warmup/measurement/fork) lives in annotations on each
`*Benchmark` class.

## Benchmarks

| Module | Class | Measures |
|---|---|---|
| harvest | `SeedSerializerBenchmark` | Seed polymorphic JSON serialize / deserialize |
| harvest | `DevcontainerParserBenchmark` | `devcontainer.json` parse (small + large) |
| nursery | `CloudInitTemplateBenchmark` | cloud-init template render |
| api | `ApiMappingBenchmark` | cloud-init status classify + GroveResponse mapping |

## Deferred (tracked separately)

Committed baselines, a `compare-microbench.sh` regression gate, and a CI job are
intentionally not part of this first cut.
