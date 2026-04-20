# Profile presets

> **TL;DR.** Four shipped YAMLs (`dev`, `prod`, `test`, `canary`) tune Pulse
> for that environment. One `spring.config.import` line per profile and you
> get the right defaults for free.

Adopting Pulse means deciding what every knob should be in dev vs prod vs
canary. Most teams default everything and discover the wrong choices in
production: 100% trace sampling on a busy service, the cardinality firewall
silently bucketing test users in CI, the trace-context guard rejecting curl
requests during local debugging.

**Pulse ships four presets that pick the right defaults for you.** Each is
a single classpath YAML file you import; everything else is overridable.

## What you get

| Preset | Mode | Sampling | Banner | Trace-guard rejects? | Cardinality firewall | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `dev` | ENFORCING | 100 % | on | no | 5 000/meter (loose) | Friendly to forgotten headers |
| `prod` | ENFORCING | 10 % | off | no | 1 000/meter | Strict, low overhead |
| `test` | ENFORCING | 0 % | off | no (filter off) | off | Quiet, deterministic, fast |
| `canary` | DRY_RUN | 100 % | on | observed | 1 000/meter, observe-only | Safe-rolling lever for new fleets |

## Turn it on

Add one line to your `application.yml`:

```yaml
spring:
  config:
    import: classpath:META-INF/pulse/prod.yml
```

Or compose with profiles:

```yaml
spring:
  profiles:
    active: prod
---
spring:
  config:
    activate.on-profile: prod
    import: classpath:META-INF/pulse/prod.yml
```

Every property the preset sets is overridable — anything declared after the
import wins.

## What it adds

Just configuration. No new beans, no new endpoints. The presets reference
the same `pulse.*` properties documented elsewhere; reading the YAMLs
themselves is the fastest way to see exactly what changes:

```bash
$ jar -tf pulse-spring-boot-starter-1.1.0.jar | grep pulse/
META-INF/pulse/dev.yml
META-INF/pulse/prod.yml
META-INF/pulse/test.yml
META-INF/pulse/canary.yml
```

## When to skip it

You manage all `pulse.*` values centrally (Vault, Spring Cloud Config,
Kubernetes ConfigMap) and don't want a starter file in the import chain.
That's fine — Pulse's defaults are also production-safe; the presets are a
shortcut, not a requirement.

## Under the hood

Spring Boot's [`spring.config.import`](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.files.importing)
loads the YAML the same way it loads `application.yml`: properties are
merged into the environment in declaration order, so anything you set in
your own config wins over the preset.

The presets do not include `enabled-when` matchers or feature-specific
`@Bean` overrides — they are pure configuration so they compose cleanly
with anything else you import. If you want a preset *plus* per-environment
beans, declare an `@Configuration` class and `@Profile`-gate it as usual.

---

**Source:** [`META-INF/pulse/`](https://github.com/arun0009/pulse/tree/main/src/main/resources/META-INF/pulse) ·
**Status:** Stable since 1.1.0
