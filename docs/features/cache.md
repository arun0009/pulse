# Cache observability (Caffeine)

> **Status:** Stable · **Config prefix:** `pulse.cache.caffeine` ·
> **Source:** [`io.github.arun0009.pulse.cache`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/cache)

## Value prop

A cache that's silently missing 90% of the time is worse than no cache —
all the latency, none of the savings. Spring Boot's Caffeine integration
collects stats only if you remember `recordStats()`, and most apps don't.
Pulse binds every `CaffeineCacheManager` to Micrometer regardless.

## What it does

When Caffeine is on the classpath, Pulse binds every
`CaffeineCacheManager` bean's existing caches to Micrometer:

- `cache.gets{result=hit|miss}`
- `cache.puts`
- `cache.evictions`
- `cache.hit_ratio` (derived)

Pulse never mutates your `Caffeine` builder configuration. If
`recordStats()` is missing on a manager, the bind happens anyway (meters
report zero) and a one-time `WARN` per manager bean is logged so you know
to add it.

## Configuration

```yaml
pulse:
  cache:
    caffeine:
      enabled: true
```

!!! note "Expanded coverage coming"

    Full reference (cache name tagging, recommended hit-ratio alerts,
    extension to other Spring `CacheManager` types) lands in a 1.0.x patch.
