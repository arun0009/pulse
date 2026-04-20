# Configuration validation

> **TL;DR.** Every `pulse.*` property has a JSR-380 constraint. A typo like
> `pulse.cardinality.max-tag-values-per-meter: -5` fails at startup with a
> Pulse-friendly message, not silently at 3 AM.

Spring Boot binds configuration without validating numeric ranges, blank
strings, or out-of-vocabulary enum values. The first time anyone sets
`pulse.cardinality.max-tag-values-per-meter=-5`, the firewall silently
disables itself and the operator never finds out — until tag explosion takes
out the metrics pipeline.

**Pulse rejects invalid configuration at startup.** Every numeric range,
header name, threshold, and ratio across the per-feature
`*Properties` records carries a JSR-380 constraint. Spring Boot fails the
`BeanFactory` instead of letting an out-of-range value through.

## What you get

A typo fails the application boot, with the exact property and the
constraint that caught it:

```text
*************************
APPLICATION FAILED TO START
*************************

Description:
Binding to target [Bindable@8a4adc4c type = io.github.arun0009.pulse.guardrails.CardinalityProperties] failed:

    Property: pulse.cardinality.max-tag-values-per-meter
    Value: -5
    Reason: must be greater than 0
```

The mistake never reaches production. The same constraint is enforced for:

- `pulse.cardinality.max-tag-values-per-meter` (must be `> 0`)
- `pulse.async.core-pool-size` / `max-pool-size` (must be `> 0`)
- `pulse.tenant.max-tag-cardinality` (must be `> 0`)
- `pulse.dependencies.health.error-rate-threshold` (must be `[0.0, 1.0]`)
- `pulse.container-memory.headroom-critical-ratio` (must be `[0.0, 1.0]`)
- `pulse.context.request-id-header` (must be non-blank)
- `pulse.slo.objectives[].target` (must be `[0.0, 1.0]`)
- ... and every other constraint listed on the per-feature
  [`*Properties` records](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse)
  (`CardinalityProperties`, `TimeoutBudgetProperties`, `TenantProperties`,
  `SloProperties`, …).

## Turn it on

Nothing. Validation is wired automatically by `@Validated` on every
per-feature `*Properties` record and Spring Boot's
`ValidationAutoConfiguration` (which is on the classpath via
`spring-boot-starter-validation`, a Pulse runtime dependency).

## What it adds

A `spring-boot-starter-validation` dependency on the runtime classpath
(~110 KB). No new beans, no new endpoints, no extra startup cost — Spring
runs the validator once when binding `pulse.*`.

## When to skip it

You can't, and you almost certainly don't want to. The fail-fast behaviour
is a property of `@Validated` on every `*Properties` record; removing it
would mean forking the starter. If a constraint is wrong, file an issue —
the right fix is a more permissive constraint, not skipping validation.

## Under the hood

Every per-feature `*Properties` record is annotated with `@Validated`.
Nested records are annotated with `@Valid` so JSR-380 walks the tree, and
individual properties carry standard constraints (`@NotBlank`, `@Positive`,
`@Min(1)`, `@DecimalMin("0.0") @DecimalMax("1.0")`, etc.).

When Spring Boot binds each `pulse.<feature>` subtree at context refresh,
the validator runs immediately. A failed constraint surfaces as a
`ConfigurationPropertiesBindException` whose root cause is the JSR-380
violation; Spring Boot's `FailureAnalyzer` formats it into the
"APPLICATION FAILED TO START" banner above.

---

**Source:** [per-feature `*Properties` records](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse) ·
**Status:** Stable since 1.1.0
