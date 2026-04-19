# Contributing to Pulse

Thanks for considering a contribution. Pulse aims to be the boring, reliable
production-correctness layer for Spring Boot. That means we're conservative
about adding scope, but very welcoming of fixes, hardening, docs, and
benchmarks.

## Quick start

```bash
git clone https://github.com/arun0009/pulse.git
cd pulse
./mvnw verify          # full check: format + tests + coverage + SBOM
```

You'll need Java 21+ and Docker (Testcontainers integration tests need a real
OTLP collector).

## Before opening a PR

```bash
./mvnw spotless:apply  # auto-format
./mvnw verify          # tests + coverage + spotless check + SBOM
./mvnw -Pbench package exec:java   # (optional) run JMH if your change touches a hot path
```

## What's in scope

✅ Anything that catches a real production failure mode by default.
✅ Hardening, perf, security, docs, examples.
✅ New backend wiring (additional propagators, new exporters).
✅ Test coverage for under-tested paths.

## What's out of scope

❌ Pulling in heavy dependencies that aren't already transitive of Spring Boot
	or OpenTelemetry.
❌ Features that 99% of teams won't use.
❌ Anything that breaks zero-config local dev — Pulse must always start
	successfully even when no OTLP endpoint is reachable.
❌ Bytecode rewriting, agents, or forks.

## Design rules

1. **Opinionated defaults, opt-out via config** — every guardrail must respect
	a `pulse.<thing>.enabled=false` switch and matching properties for the
	knobs that matter.
2. **No unbounded cardinality** — any meter you add must be guarded by the
	firewall or by static enums.
3. **No silent failures** — if Pulse can't do its job, it logs *why* once at
	startup, not on every request.
4. **Tests must show behavior** — no `@Mock` ceremony for the sake of it. If
	the test wouldn't catch a regression, don't write it.

## Commit style

Conventional Commits recommended but not enforced:

```
feat(timeout-budget): propagate residual budget to WebClient outbound
fix(cardinality): respect exemptions for HTTP method tag
docs: clarify zero-config local dev behaviour
```

## Questions

Open a [discussion](https://github.com/arun0009/pulse/discussions) for design
questions; open an [issue](https://github.com/arun0009/pulse/issues) for bugs
and concrete proposals.
