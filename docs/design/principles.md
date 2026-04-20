# Design principles

Pulse is opinionated. The opinions are the product. This page collects
the patterns the codebase uses repeatedly so contributors and integrators
know when to reach for them — and when not to.

If you're proposing a new feature, every entry below is a question you
should answer before opening the PR.

---

## 1. Opt-out, never opt-in

**Pulse on the classpath = Pulse on.** Every feature ships with
`enabled: true` and a sensible default. Users opt *out* with
`enabled: false`. The day-one experience is "add the dependency, get
production observability." The day-two experience is "turn off the one
thing that's noisy for my workload."

This rules out:

- Annotations that have to be sprinkled to activate a feature
  (`@EnableXxx`). Spring already auto-configures everything; piling on
  more annotations tells users "we don't trust our defaults."
- Configuration that requires choosing a profile or `spring.config.import`
  to activate. The starter is the activation.
- Per-bean opt-in. If you have to add `@Observable` to every controller,
  the feature isn't really "batteries included."

The exceptions are features that have *real* operational cost and no
sensible default — `pulse.dependencies.health.critical` is a list, and
the empty list is the only safe default. There's no toggle hiding a
behaviour change.

## 2. Declarative-first, with an imperative escape hatch

YAML is the front door. A bean is the side door. Most users never need
the side door, but it's always there.

The pattern is consistent:

```yaml
pulse:
  some-feature:
    enabled-when:
      header-not-equals:
        client-id: test-client-id
      # OR — when declarative isn't enough:
      bean: myCustomMatcher
```

Why both?

- **Declarative wins on portability.** Ops can change the rule in the
  config repo without rebuilding the app.
- **Bean wins on expressiveness.** *"Active only between 02:00–04:00 UTC
  for tenants on the free plan"* will never fit a header-equals map, and
  inventing a DSL for it would be a worse API than just letting the user
  write Java.

If both are configured on the same block, the bean wins and Pulse logs a
warning at startup. The user is told once, loudly, and the runtime is
predictable.

## 3. Per-request gates use a single SPI: `PulseRequestMatcher`

Every feature that runs in the request scope and might want to skip
*some* requests reuses the same primitive — a one-method
`PulseRequestMatcher` interface compiled from the same
`enabled-when` schema.

This buys:

- **One thing to learn.** A user who configured `enabled-when` for
  trace-guard already knows how to configure it for timeout-budget,
  database, dependencies, fan-out, and exception fingerprints.
- **One place to test.** Matcher logic lives in
  `PulseRequestMatcherFactory`. The feature filters get the compiled
  matcher and ask one question: `gate.matches(request)`.
- **No accidental new vocabulary.** New features that need a runtime
  gate get one for free; no new property names, no new mental model.

Features that *don't* fit (cardinality firewall on the meter pipeline,
PII masking per log event, sampling per span) are explicitly NOT made to
fit. They use the right native extension point — `MeterFilter`, log
event masking, OpenTelemetry `Sampler` — even though that means the user
has to learn a different (existing) API for those three.

## 4. Extension points are SPIs, not annotations

When a feature has a decision point that users want to override
("classify this dependency", "fingerprint this exception",
"extract this tenant"), Pulse defines a single-method interface and
auto-wires the user's `@Bean` if they declare one.

| Decision | SPI | Default |
| --- | --- | --- |
| Logical name for an outbound call | `DependencyClassifier` | Host-table lookup via `pulse.dependencies.map` |
| Stable id for an unhandled exception | `ErrorFingerprintStrategy` | SHA-256 over class + top stack frames |
| Tenant id for the inbound request | `TenantExtractor` | Header / JWT claim / subdomain (per config) |
| Should this feature run for this request | `PulseRequestMatcher` | Always-true (matches every request) |
| Add fields to the request context | `ContextContributor` | None — pure extension point |

Why interfaces and not annotations? Annotations spread across the
codebase make the answer to "where does the dep tag come from?" a global
search. A `@Bean DependencyClassifier` is one file with one method; the
answer is the bean.

## 5. Fail fast at startup, fail open at runtime

Every misconfiguration that can be detected at startup *is* detected at
startup, with a clear message naming the property and the expected
shape. Examples:

- `enabled-when.bean: foo` where `foo` doesn't exist → `IllegalStateException`
  before the first request.
- `enabled-when.bean: foo` where `foo` is the wrong type → same, with
  the actual type in the message.
- `pulse.slo.objectives` with an unknown SLI name → startup fails with
  the list of valid names.

Once the app is running, Pulse defaults to **fail open**: a matcher that
can't evaluate (no inbound request bound — e.g. inside a `@Scheduled`
job that calls an outbound HTTP service) returns `true` so the feature
runs. A `ContextContributor` that throws is logged once and skipped on
subsequent requests. A meter filter that mis-tags overflows to the
designated bucket.

The principle: **never silently kill production observability** because
of a runtime edge case the user couldn't have known about at startup.

## 6. One bean per feature, gated by `@ConditionalOnMissingBean`

Every Pulse feature ships exactly one publicly-named bean, and every
such bean carries `@ConditionalOnMissingBean`. Users override the whole
feature by declaring their own bean of the same type — no Pulse-specific
unregistration ritual, no system properties, no profile gymnastics.

The corollary: Pulse's bean class is a public class with a public
constructor. Whatever wiring Pulse does, the user can replicate or
delegate to.

## 7. Cardinality is a first-class concern

Every metric tag is justified before it ships. Three rules:

1. **Bounded by something the operator already knows.** Endpoint =
   Spring route template (already bounded by your controllers).
   Dependency = entry from `pulse.dependencies.map` (already bounded by
   your config). Tenant = `pulse.tenant.max-tag-cardinality` (explicitly
   capped, with overflow bucket).
2. **The cardinality firewall is on by default** for any meter
   prefix Pulse owns. Excess values bucket to `OVERFLOW` and a one-time
   WARN log line fires.
3. **No `userId` tag, ever.** No `requestId` tag, ever. No timestamp
   tag, ever. Those go on logs and traces — never on metrics.

## 8. The `/actuator/pulse` endpoint is the source of truth

If a feature exists, the actuator endpoint reports its current
configuration and observed health. "What is Pulse doing in this pod?" is
a single HTTP call away — no log scraping, no JMX trees, no
classpath-introspection scripts.

This shapes design: a feature that can't faithfully report itself isn't
done. The endpoint is what makes Pulse legible to the operator.

## 9. Honesty about what we don't do

Each feature page has a "When to skip it" section. Pulse refuses to
pretend its defaults are universally right. The starter ships with
opinions, and one of those opinions is that you should turn things off
when they don't apply to your workload.

The same honesty applies to scope. Pulse is a **starter**, not a
platform: it bundles best-practice defaults for the OpenTelemetry +
Micrometer + Spring stack. It does not ship a collector, a backend, a
storage layer, or a query language.

---

## Adopting these principles

If you're adding a new feature to Pulse, the checklist is:

- [ ] `pulse.<feature>.enabled` exists, defaults to `true`, gates everything in this feature.
- [ ] If the feature is per-request, `enabled-when` is wired through `PulseRequestMatcherFactory`.
- [ ] Decision points are SPIs (`@FunctionalInterface` + default impl), not annotations.
- [ ] The bean is `@ConditionalOnMissingBean` so users can override it.
- [ ] Misconfiguration fails at startup; runtime edge cases fail open.
- [ ] Every metric tag is justified in the property javadoc.
- [ ] `/actuator/pulse` reports the feature's configuration and health.
- [ ] The docs page leads with the problem, then "what you get", then config, then "when to skip it".

If you're integrating Pulse and want to extend it, the corresponding
checklist is:

- [ ] Override declarative behaviour via `application.yml` first.
- [ ] When that isn't enough, drop in an SPI bean.
- [ ] When *that* isn't enough, declare a `@Bean` of the Pulse type and
  Pulse's default disappears via `@ConditionalOnMissingBean`.

You will rarely reach step 3.
