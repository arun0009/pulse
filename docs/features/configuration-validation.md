# Configuration validation

> **TL;DR.** Every `pulse.*` property has a JSR-380 constraint. A typo like
> `pulse.sampling.probability: 1.5` fails at startup with a Pulse-friendly
> message, not silently at 3 AM.

Spring Boot binds configuration without validating numeric ranges, blank
strings, or out-of-vocabulary enum values. The first time anyone sets
`pulse.sampling.probability=1.5` (the OTel API expects 0.0–1.0), the SDK
silently treats it as 1.0 and the operator never finds out — until they
notice traces are being recorded at full rate in production.

**Pulse rejects invalid configuration at startup.** Every numeric range,
header name, threshold, and ratio in `PulseProperties` carries a JSR-380
constraint. Spring Boot fails the `BeanFactory` instead of letting an
out-of-range value through.

## What you get

A typo fails the application boot, with the exact property and the
constraint that caught it:

```text
*************************
APPLICATION FAILED TO START
*************************

Description:
Binding to target [Bindable@8a4adc4c type = io.github.arun0009.pulse.autoconfigure.PulseProperties] failed:

    Property: pulse.sampling.probability
    Value: 1.5
    Reason: must be less than or equal to 1.0
```

The mistake never reaches production. The same constraint is enforced for:

- `pulse.cardinality.max-tag-values-per-meter` (must be `> 0`)
- `pulse.async.core-pool-size` / `max-pool-size` (must be `> 0`)
- `pulse.tenant.max-tag-cardinality` (must be `> 0`)
- `pulse.dependencies.health.error-rate-threshold` (must be `[0.0, 1.0]`)
- `pulse.container-memory.headroom-critical-ratio` (must be `[0.0, 1.0]`)
- `pulse.context.request-id-header` (must be non-blank)
- `pulse.slo.objectives[].target` (must be `[0.0, 1.0]`)
- ... and every other constraint listed on the
  [`PulseProperties`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/autoconfigure/PulseProperties.java)
  source.

## Turn it on

Nothing. Validation is wired automatically by `@Validated` on
`PulseProperties` and Spring Boot's `ValidationAutoConfiguration` (which is
on the classpath via `spring-boot-starter-validation`, a Pulse runtime
dependency).

## What it adds

A `spring-boot-starter-validation` dependency on the runtime classpath
(~110 KB). No new beans, no new endpoints, no extra startup cost — Spring
runs the validator once when binding `pulse.*`.

## When to skip it

You can't, and you almost certainly don't want to. The fail-fast behaviour
is a property of `@Validated` on `PulseProperties`; removing it would mean
forking the starter. If a constraint is wrong, file an issue — the right
fix is a more permissive constraint, not skipping validation.

## Under the hood

`PulseProperties` is annotated with `@Validated`. Every nested record field
is annotated with `@Valid` so JSR-380 walks the tree, and individual
properties carry standard constraints (`@NotBlank`, `@Positive`,
`@Min(1)`, `@DecimalMin("0.0") @DecimalMax("1.0")`, etc.).

When Spring Boot binds the `pulse.*` tree at context refresh, the validator
runs immediately. A failed constraint surfaces as a
`ConfigurationPropertiesBindException` whose root cause is the JSR-380
violation; Spring Boot's `FailureAnalyzer` formats it into the
"APPLICATION FAILED TO START" banner above.

---

**Source:** [`PulseProperties.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/autoconfigure/PulseProperties.java) ·
**Status:** Stable since 1.1.0
