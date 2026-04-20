# Cache observability (Caffeine)

> **TL;DR.** Forces `recordStats()` on every Caffeine cache and binds them
> to Micrometer. Hit/miss/eviction metrics are always there, even when
> someone forgot to enable them.

A cache silently missing 90% of the time is worse than no cache — all the
latency, none of the savings. Spring Boot's Caffeine integration only
collects stats if you remembered `recordStats()` on every builder, and most
apps haven't.

**Pulse binds every `CaffeineCacheManager` to Micrometer regardless** of
whether stats were enabled — so the metrics are always there, even when
you forgot.

## What you get

```promql
1 - (sum by (cache) (rate(cache_gets_total{result="miss"}[5m]))
     /
     sum by (cache) (rate(cache_gets_total[5m])))
```

The hit ratio per cache. A cache hovering near 0 is a cache that should be
deleted; one near 1 is a cache pulling its weight.

## Turn it on

Nothing. Automatic when Caffeine is on the classpath.

If a manager bean was created without `recordStats()`, the bind happens
anyway (meters report zero) and a one-time `WARN` per manager is logged so
you know to add it.

## What it adds

| Metric | Meaning |
| --- | --- |
| `cache.gets` (tag `result=hit\|miss`) | Hit/miss counts per cache |
| `cache.puts` | Insertions |
| `cache.evictions` | Evictions |
| `cache.hit_ratio` | Derived from gets |

Pulse never mutates your `Caffeine` builder configuration.

## When to skip it

If you bind your caches manually through `CaffeineCacheMetrics` and don't
want a duplicate registration:

```yaml
pulse:
  cache:
    caffeine:
      enabled: false
```

---

**Source:** [`io.github.arun0009.pulse.cache`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/cache) ·
**Status:** Stable since 1.0.0
