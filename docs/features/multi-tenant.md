# Multi-tenant context

> **TL;DR.** Tenant id from header / JWT claim / subdomain, threaded through
> MDC, baggage, outbound headers, and (opt-in) metric tags. Now *"slow
> checkout"* is *"slow checkout for which tenant?"*.

In a multi-tenant SaaS, *"slow checkout"* is meaningless without knowing
*"slow checkout for which tenant?"* Most teams thread the tenant through by
hand, drop it in half their log lines, and never tag the metrics that matter.

**Pulse threads the tenant identity through the whole request automatically.**
Header, JWT claim, or subdomain — pick a source. Pulse handles MDC, baggage,
outbound headers, and (opt-in) metric tags.

## What you get

Every log line carries the tenant:

```json
{
  "message": "placing order",
  "trace_id": "...",
  "pulse.tenant.id": "acme-corp",
  "user.id": "u-981234"
}
```

Every outbound request carries it too — so the downstream service sees the
same value without you wiring anything. And one LogQL or PromQL query
answers tenant-scoped questions:

```
{service_name="checkout"} | json | pulse_tenant_id="acme-corp" | duration_ms > 1000
```

## Turn it on

Pick which sources Pulse should look at, in priority order:

```yaml
pulse:
  tenant:
    enabled: true
    extractors:
      - header        # Pulse-Tenant-Id (default)
      # - jwt         # configurable claim name on a Bearer token
      # - subdomain   # acme.example.com → tenant "acme"
```

Defaults to header-only.

## What it adds

| Where | Key |
| --- | --- |
| MDC | `pulse.tenant.id` |
| OTel baggage | `pulse.tenant.id` |
| HTTP / Kafka outbound header | `Pulse-Tenant-Id` (configurable) |
| Optional metric tag | `tenant` (only on meters you opt in) |

A separate cap (`pulse.tenant.max-tag-cardinality`, default 100) limits the
tenant tag *independently* of the global [cardinality firewall](cardinality-firewall.md),
so a runaway tenant ID never bucket-spams your business metrics.

## When to skip it

If you're not multi-tenant, or the tenant is already on your existing
correlation header:

```yaml
pulse:
  tenant:
    enabled: false
```

## Custom sources

Implement the `TenantExtractor` SPI for anything else (path segment, custom
JWT shape, etc.) — see the [design notes](../design/multi-tenant-context.md)
for the rationale and SPI shape.

---

**Source:** [`io.github.arun0009.pulse.tenant`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/tenant) ·
**Design notes:** [Multi-tenant context](../design/multi-tenant-context.md) ·
**Status:** Stable since 1.0.0
