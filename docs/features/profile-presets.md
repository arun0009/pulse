# Profile presets

> **TL;DR.** Four shipped Spring profiles (`pulse-dev`, `pulse-prod`,
> `pulse-test`, `pulse-canary`) tune Pulse for that environment. Activate
> the matching profile (or just let Pulse auto-detect `dev`/`prod`/`test`/
> `canary` in your active profiles) and you get the right defaults for free.

Adopting Pulse means deciding what every knob should be in dev vs prod vs
canary. Most teams default everything and discover the wrong choices in
production: 100 % trace sampling on a busy service, the cardinality firewall
silently bucketing test users in CI, the trace-context guard rejecting curl
requests during local debugging.

**Pulse ships four presets that pick the right defaults for you.** Each one
is a standard Spring profile YAML at the standard location
(`application-pulse-{env}.yml`) — Spring Boot loads them automatically when
the matching profile is active. Everything is overridable.

## What you get

| Preset | Mode | Sampling | Banner | Trace-guard rejects? | Cardinality firewall | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `pulse-dev` | ENFORCING | 100 % | on | no | 5 000/meter (loose) | Friendly to forgotten headers |
| `pulse-prod` | ENFORCING | 10 % | off | no | 1 000/meter | Strict, low overhead |
| `pulse-test` | ENFORCING | 0 % | off | no (filter off) | off | Quiet, deterministic, fast |
| `pulse-canary` | DRY_RUN | 100 % | on | observed | 1 000/meter, observe-only | Safe-rolling lever for new fleets |

## Turn it on

Two ways. Pick one.

**1. Just activate your environment profile (auto-detect).** Pulse ships an
`EnvironmentPostProcessor` that recognises common environment names in
`spring.profiles.active` and appends the matching `pulse-*` profile so the
preset YAML gets picked up. Defaults to **on**.

```bash
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

Pulse sees `prod` in the active profiles and quietly adds `pulse-prod`.
Spring Boot then loads `application-pulse-prod.yml` from the classpath.

The recognised names (extensible — see "Customising" below):

- `dev` / `development` / `local` → `pulse-dev`
- `prod` / `production` → `pulse-prod`
- `test` / `integration` → `pulse-test`
- `canary` / `shadow` → `pulse-canary`

**2. Be explicit.** Pulse ships these as ordinary Spring profiles, so the
plain Spring activation works and nothing about Pulse is special:

```yaml
spring:
  profiles:
    active: prod,pulse-prod
```

Or, equivalently, on the command line:

```bash
SPRING_PROFILES_ACTIVE=prod,pulse-prod ./mvnw spring-boot:run
```

Every property the preset sets is overridable — set the same key in your own
profile YAML or `application.yml` and your value wins.

## Customising

Teach Pulse about your own profile names:

```yaml
pulse:
  profile-presets:
    presets:
      stage: pulse-prod
      qa: pulse-test
```

Or turn the auto-detection off entirely and force everyone to opt in
explicitly:

```yaml
pulse:
  profile-presets:
    auto-apply: false
```

## What it adds

Just configuration. No new beans (other than the EPP). The presets reference
the same `pulse.*` properties documented elsewhere; reading the YAMLs
themselves is the fastest way to see exactly what changes:

```bash
$ jar -tf pulse-spring-boot-starter-2.0.0.jar | grep '^application-pulse'
application-pulse-dev.yml
application-pulse-prod.yml
application-pulse-test.yml
application-pulse-canary.yml
```

## When to skip it

You manage all `pulse.*` values centrally (Vault, Spring Cloud Config,
Kubernetes ConfigMap) and don't want a profile file in the chain. That is
fine — set `pulse.profile-presets.auto-apply=false` and don't activate any
`pulse-*` profile. Pulse's compiled-in defaults are also production-safe;
the presets are a shortcut, not a requirement.

## Under the hood

The presets are plain Spring profile YAMLs at the standard location, so
Spring Boot's [config-data loader](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.files)
picks them up like any other `application-{profile}.yml` — properties merge
into the environment in declaration order, so anything you set in your own
config wins over the preset.

`PulseProfilePresetEnvironmentPostProcessor` runs at order
`HIGHEST_PRECEDENCE + 5`, before Spring Boot's
`ConfigDataEnvironmentPostProcessor` (`HIGHEST_PRECEDENCE + 10`), so the
profile it appends is visible by the time Spring scans for matching
`application-{profile}.yml` files.

The presets are pure configuration — no `enabled-when` matchers, no
feature-specific `@Bean` overrides — so they compose cleanly with anything
else you load. If you want a preset *plus* per-environment beans, declare
an `@Configuration` class and `@Profile`-gate it as usual.

---

**Source:** [`application-pulse-*.yml`](https://github.com/arun0009/pulse/tree/main/src/main/resources) · [`PulseProfilePresetEnvironmentPostProcessor`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/autoconfigure/internal/PulseProfilePresetEnvironmentPostProcessor.java) ·
**Status:** Stable since 1.1.0
