# API stability

> **TL;DR.** Public `pulse.*` properties, the `/actuator/pulse*` endpoint
> shape, metric names, and types in non-`internal` packages are **stable
> across 2.x minor versions**. Everything under an `.internal` package, the
> auto-configuration class names, and the bean names listed in the SPI
> catalog are deliberately unstable.

Pulse 2.0 introduced a hard line between the API you're meant to depend on
and the wiring that makes it work. This page is the explicit contract.

## What's stable in 2.x

Changes to these break a `2.0 → 2.x` upgrade and will only land on a major
version bump:

- **Configuration keys** under `pulse.*`. Renames go through a deprecation
  cycle: the old key still binds, a startup warning fires, and the
  `additional-spring-configuration-metadata.json` entry carries
  `level: error` plus a replacement pointer.
- **Public types** in non-`internal` packages:
  `io.github.arun0009.pulse.<feature>` (e.g. `SpanEvents`,
  `PulseEventContext`, `DependencyClassifier`, `ErrorFingerprintStrategy`,
  `TenantExtractor`, `ContextContributor`, `PulseRequestMatcher`,
  `HostNameProvider`, `ResourceAttributeResolver`,
  `PulseEnforcementMode`).
- **Metric names** and their tag keys: `pulse.*` meters and their
  low-cardinality tags are pinned. Tag **values** may expand (e.g. a new
  `reason` bucket) within a minor release.
- **Actuator endpoint shapes**: `/actuator/pulse`,
  `/actuator/pulse/{slo,effective-config,runtime,config-hash,enforcement}`.
  Existing JSON keys keep their meaning; new keys may be added.
- **Auto-configuration activation contract**: each feature's top-level
  enable toggle (`pulse.<feature>.enabled`) and, where declared, its
  `enabled-when` block.
- **SPI contracts** listed in [Extending Pulse (SPIs)](spi.md).
- **MDC keys** Pulse writes: `traceId`, `requestId`, `userId`, `tenantId`,
  `priority`, `retry.depth`, `service.name`, `deployment.environment`.
- **HTTP headers** Pulse reads / stamps: `traceparent`, `tracestate`,
  `baggage`, `Pulse-Request-Id`, `Pulse-Timeout-Ms`, `Pulse-Tenant-Id`,
  `Pulse-Priority`, `Pulse-Retry-Depth`.

## What's explicitly unstable

These may be renamed, moved, or removed in any minor release without a
deprecation cycle. Do not import or depend on them:

- **`*.internal.*` packages.** Everything under
  `io.github.arun0009.pulse.<feature>.internal` is wiring — concrete
  `@Configuration` classes, `BeanPostProcessor` factories, instrumented
  `@Bean` names that aren't on the override-worthy list, private helper
  classes. Spring Boot's auto-configuration contract is "wire by
  activation property, not by class name" and Pulse follows that strictly.
- **Auto-configuration class names.** Reference features by their
  activation property or `@ConditionalOnMissingBean` override, never by
  the FQCN of the `@AutoConfiguration` that wires them.
- **Built-in `ObservationHandler<PulseEventContext>` implementations.**
  Replace behavior by registering your own handler bean; do not extend or
  reference `PulseEventCounterObservationHandler`,
  `PulseEventSpanObservationHandler`,
  `PulseEventLoggingObservationHandler` directly.
- **Internal log messages.** The *names* of Pulse's log events
  (`Pulse retry-amplification detected`, etc.) are not an API. If you
  parse them, treat each string as best-effort.
- **Bean names not in the override table.** [The SPI catalog](spi.md)
  lists the bean names gated on `@ConditionalOnMissingBean`. Anything
  outside that table — in particular additive `BeanPostProcessor`s and
  chain terminals — may be renamed.

## Deprecation policy

When a stable surface must change:

1. The new surface lands in a minor release, alongside the existing one.
   Both work simultaneously.
2. The old surface is marked `@Deprecated(forRemoval = true)` (for code)
   or gets a `"deprecation"` entry with `"level": "error"` and a
   `"replacement"` pointer (for configuration keys).
3. A startup log line fires whenever the old surface is actually used, so
   `grep WARN` on boot logs is enough to spot every deprecated call site.
4. The old surface is removed in the **next major** release (2.x
   deprecation → removed in 3.0).

Example: `pulse.sampling.probability` was deprecated in 2.0 in favour of
Spring Boot's `management.tracing.sampling.probability`. It will be
removed in Pulse 3.0.

## Java and Spring Boot version policy

- **Java baseline** moves only on a minor release and only if a mainstream
  LTS is End-Of-Life. 2.0 requires Java 21; CI also runs 25.
- **Spring Boot** minor upgrades land in Pulse minor releases *when they
  are a no-op for consumers*. A Boot major upgrade (e.g. 4 → 5) is a
  Pulse major upgrade.

## Semantic versioning

Pulse follows [SemVer](https://semver.org/) **for everything listed under
"What's stable in 2.x"**. Purely additive changes — new feature flags, new
optional properties with safe defaults, new optional SPIs — ship in
minor releases.

## "My build broke on a patch release"

Pulse patch releases (`2.x.y → 2.x.(y+1)`) only ship bug fixes, security
updates, and dependency bumps that don't require consumer action. If a
patch release breaks your build, [open an issue](https://github.com/arun0009/pulse/issues/new)
— that's a bug in the release, not your code.

---

**See also:** [Concepts](concepts.md) ·
[Extending Pulse (SPIs)](spi.md) ·
[Web stack (Servlet-only)](web-stack.md) ·
[Configuration reference](configuration.md)
