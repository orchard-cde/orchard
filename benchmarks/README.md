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
| apiary | `BeeKeeperAdapterBenchmark` | BeeKeeper adapter: config render, install / release / smoke / inspect, registry lookup |

> Note: `CloudInitTemplateBenchmark.render` mirrors production, which re-reads the
> template from the classpath on every call — so its number includes resource-stream
> I/O, not just string substitution. Don't compare it like-for-like against the
> pure-CPU serialize/parse benchmarks.

> Note: every `BeeKeeper` method returns a `CompletableFuture` from a virtual-thread
> executor, so `install`, `release*`, `smoke`, and `inspect` are dominated by dispatch
> overhead rather than by the work they wrap. `renderConfig` is benchmarked directly,
> bypassing the future, because config rendering is only a few percent of the whole-method
> `install` score — a 2x regression there moves `install` by less than the 10% comparison
> threshold, so `install` alone cannot detect it. Watch `renderConfig` for config-path
> changes and `install` for end-to-end adapter overhead. Observed on an Apple-silicon dev
> host: `renderConfig` ~0.36 µs, `install` ~7.85 µs — a ~22x gap that is almost
> entirely virtual-thread dispatch. Don't compare these against the pure-CPU
> `SeedSerializerBenchmark` numbers.

## Deferred (tracked separately)

Committed baselines, a `compare-microbench.sh` regression gate, and a CI job are
intentionally not part of this first cut.
