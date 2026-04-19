# Conditional features (`enabled-when`)

> **Status:** Stable since 1.1.0
> **Source:** [`PulseRequestMatcher`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/core/PulseRequestMatcher.java) · [`PulseRequestMatcherProperties`](https://github.com/arun0009/pulse/blob/main/src/main/java/io/github/arun0009/pulse/autoconfigure/PulseRequestMatcherProperties.java)

## Why

Some features need a finer toggle than `enabled: true|false`. Synthetic
monitoring traffic shouldn't trip the trace-context guard. Smoke tests don't
need PII masking on their fake payloads. A trusted internal admin caller can
bypass the cardinality firewall safely.

Pulse exposes these as a single, uniform pattern: every feature that supports
runtime gating accepts an **`enabled-when:`** block alongside its existing
`enabled:` flag. The block is a declarative request matcher — header equality,
prefix match, path matching — compiled once at startup, consulted on every
request via a single virtual call.

## The matcher schema

The same fields apply to every feature that exposes `enabled-when`:

| Field | Type | Semantics |
| --- | --- | --- |
| `header-equals` | `Map<String, String>` | Every listed header must equal its value (AND across the map). Missing header counts as "not equal" → matcher returns `false`. |
| `header-not-equals` | `Map<String, String>` | No listed header may equal its forbidden value. Missing header counts as "not equal" → passes. |
| `header-prefix` | `Map<String, String>` | Every listed header must start with the given prefix. |
| `path-matches` | `List<String>` | Request URI must start with at least one prefix. |
| `path-excludes` | `List<String>` | Request URI must not start with any of these. Takes precedence over `path-matches`. |
| `bean` | `String` | Name of a `PulseRequestMatcher` bean to delegate to. When set, declarative fields are ignored. |

**Combination rule:** AND across populated fields. An empty/unset block matches
every request, which is the default and matches Pulse's pre-1.1 behaviour.

## Examples

### Skip trace-guard for synthetic monitoring

```yaml
pulse:
  trace-guard:
    enabled: true
    enabled-when:
      header-not-equals:
        client-id: test-client-id
```

Every real request still passes through the guard; requests from the synthetic
client bypass it entirely. No counters emitted, no warnings logged, downstream
chain still runs normally.

### Skip the user-agent that your probes use

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

Both conditions must agree before the guard runs. AND semantics throughout —
no boolean expression DSL to memorise.

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

The `_matchers` key is YAML scaffolding — Pulse never reads it. The `&not-synthetic`
anchor and `*not-synthetic` aliases are pure YAML, no Pulse-specific machinery.

### Imperative escape hatch

When a declarative rule cannot express your logic ("active only between
02:00-04:00 UTC for tenants on the free plan"), implement
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

If both `bean:` and declarative fields are set on the same block, the bean
wins and Pulse logs a warning. If the bean name doesn't resolve to a
`PulseRequestMatcher`, application startup fails fast — never silently at the
first request.

## Failure semantics

| Misconfiguration | Behaviour |
| --- | --- |
| Empty / unset `enabled-when` | Matcher always matches (feature always runs) — pre-1.1 default. |
| `bean:` references a missing bean | `IllegalStateException` at startup. Application does not start. |
| `bean:` references a bean of the wrong type | `IllegalStateException` at startup with the actual type in the message. |
| Header listed in `header-equals` is absent at request time | Matcher returns `false`, feature skipped. |
| Header listed in `header-not-equals` is absent | Matcher returns `true`, feature runs (fail-open). |
| Matcher itself throws | The exception propagates up the filter chain. Implement bean matchers defensively — return `true` on doubt. |

## Features that support `enabled-when` today

- `pulse.trace-guard.enabled-when` (since 1.1.0)

The pattern will be adopted incrementally by features where dynamic gating is
genuinely useful — cardinality firewall, PII masking, timeout-budget,
sampling. Feature pages will list `enabled-when` in their config table when
support lands.

## What this is not

- **Not a tag system.** It does not classify requests into named groups for
  downstream features to read. If you need that, request a "request
  classifier" issue and we can discuss whether the use case warrants the extra
  concept.
- **Not a way to silently drop observability.** The matcher only short-circuits
  the **owning feature**. Other Pulse features keep running, the downstream
  filter chain keeps running, your application code is unaffected.
- **Not a replacement for `excludePathPrefixes`.** The path exclusion list on
  features like trace-guard is a coarse, always-on guard against probing
  endpoints. `enabled-when` is the dynamic, request-level gate layered on top.
