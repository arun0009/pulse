# Conditional features (`enabled-when`)

> **TL;DR.** A uniform `enabled-when:` block on every Pulse feature. Skip
> the trace-guard for synthetic monitoring, the cardinality firewall for
> a trusted internal caller, etc. — without setting `enabled: false`
> globally.

Some features need a finer toggle than `enabled: true|false`. Synthetic
monitoring traffic shouldn't trip the trace-context guard. Smoke tests
don't need PII masking on their fake payloads. A trusted internal admin
caller can bypass the cardinality firewall safely. Setting
`enabled: false` globally to handle these is a foot-gun; you almost always
forget to turn it back on.

**Pulse exposes a uniform `enabled-when:` block** on every feature that
supports runtime gating. Same schema, same combination rules, same opt-out.

## What you get

A single declarative rule, one place to look:

```yaml
pulse:
  trace-guard:
    enabled-when:
      header-not-equals:
        client-id: test-client-id
```

Real requests still pass through the guard. Synthetic requests bypass it
entirely — no counters, no warnings, the downstream chain still runs.

## The matcher schema

Same fields apply on every feature that exposes `enabled-when`:

| Field | Type | Semantics |
| --- | --- | --- |
| `header-equals` | `Map<String, String>` | All listed headers must equal their value. Missing header → matcher returns `false`. |
| `header-not-equals` | `Map<String, String>` | No listed header may equal its forbidden value. Missing header passes. |
| `header-prefix` | `Map<String, String>` | All listed headers must start with the given prefix. |
| `path-matches` | `List<String>` | Request URI must start with at least one prefix. |
| `path-excludes` | `List<String>` | Request URI must not start with any of these. Wins over `path-matches`. |
| `bean` | `String` | Name of a `PulseRequestMatcher` bean to delegate to. When set, declarative fields are ignored. |

**Combination rule:** AND across populated fields. An empty / unset block
matches every request — i.e. the feature runs unconditionally.

## More examples

### Skip the user-agent your probes use

```yaml
pulse:
  trace-guard:
    enabled-when:
      header-prefix:
        user-agent: "PulseProbe/"
```

### Combine multiple conditions

```yaml
pulse:
  trace-guard:
    enabled-when:
      header-not-equals:
        client-id: test-client-id
      path-excludes:
        - /internal
        - /healthz-deep
```

Both conditions must agree before the guard runs. AND throughout — no
boolean expression DSL to memorise.

### Reuse one rule across multiple features (YAML anchors)

```yaml
pulse:
  _matchers:
    not-synthetic: &not-synthetic
      header-not-equals:
        x-pulse-synthetic: "true"
  trace-guard:
    enabled-when: *not-synthetic
  # Future features that adopt enabled-when can reuse the anchor:
  # cardinality-firewall:
  #   enabled-when: *not-synthetic
```

The `_matchers` key is YAML scaffolding — Pulse never reads it. Anchors and
aliases are pure YAML, no Pulse-specific machinery.

### Imperative escape hatch

When a declarative rule cannot express your logic — *"active only between
02:00–04:00 UTC for tenants on the free plan"* — implement
[`PulseRequestMatcher`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/core/PulseRequestMatcher.java)
and reference it by bean name:

```java
@Bean
PulseRequestMatcher freePlanWindowMatcher(TenantService tenants) {
    return request -> {
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null) return true;
        if (!tenants.isOnFreePlan(tenantId)) return true;
        int hour = LocalDateTime.now(ZoneOffset.UTC).getHour();
        return hour >= 2 && hour < 4;
    };
}
```

```yaml
pulse:
  trace-guard:
    enabled-when:
      bean: freePlanWindowMatcher
```

If both `bean:` and declarative fields are set, the bean wins and Pulse
logs a warning. If the bean name doesn't resolve to a `PulseRequestMatcher`,
startup fails fast — never silently at the first request.

## Failure semantics

| Misconfiguration | Behaviour |
| --- | --- |
| Empty / unset `enabled-when` | Matcher always matches (feature always runs) |
| `bean:` references a missing bean | `IllegalStateException` at startup. App does not start. |
| `bean:` references a bean of the wrong type | `IllegalStateException` at startup with the actual type in the message |
| Header in `header-equals` is absent at request time | Matcher returns `false`, feature skipped |
| Header in `header-not-equals` is absent | Matcher returns `true`, feature runs (fail-open) |
| Matcher itself throws | Exception propagates up the filter chain. Implement bean matchers defensively — return `true` on doubt. |

## Features that support `enabled-when` today

| Feature | Property | Scope when matcher rejects |
| --- | --- | --- |
| Trace-context guard | `pulse.trace-guard.enabled-when` | Skips missing-context detection for the request. Chain still runs. |
| Timeout-budget | `pulse.timeout-budget.enabled-when` | No budget established on baggage; downstream sees `Optional.empty()`. |
| Database (N+1) | `pulse.db.enabled-when` | Per-request statement scope is never opened; no `pulse.db.*` metric for the request. |
| Dependencies (per-call RED) | `pulse.dependencies.enabled-when` | Outbound `pulse.dependency.*` metrics are not recorded for that inbound request. Outside a request scope (scheduled jobs, Kafka consumers) Pulse fails open and still records. |
| Fan-out (per-request width) | `pulse.dependencies.enabled-when` *(shared)* | `pulse.request.fan_out{,_high}` and `pulse.request.distinct_dependencies` are not recorded. |
| Exception fingerprints | `pulse.exception-handler.enabled-when` | Pulse still returns a baseline `ProblemDetail` (so the caller is not left with a Spring default error page) but skips fingerprinting, the MDC stamp, the span attribute, and the `pulse.errors.unhandled` increment. |

Features where `enabled-when` doesn't fit (cardinality firewall, PII
masking, sampling) use their own native extension points — meter filters,
log-event masking, and the OpenTelemetry `Sampler` API respectively.

## What this is not

- **Not a tag system.** It does not classify requests into named groups
  for downstream features to read.
- **Not a way to silently drop observability.** The matcher only
  short-circuits the **owning feature**. Other Pulse features keep
  running, the downstream filter chain keeps running, your application
  code is unaffected.
- **Not a replacement for `exclude-path-prefixes`.** The path exclusion
  list on features like trace-guard is a coarse, always-on guard against
  probing endpoints. `enabled-when` is the dynamic, request-level gate
  layered on top.

---

**Source:** [`PulseRequestMatcher.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/core/PulseRequestMatcher.java) ·
[`PulseRequestMatcherProperties.java`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/autoconfigure/PulseRequestMatcherProperties.java) ·
**Status:** Stable since 1.1.0
