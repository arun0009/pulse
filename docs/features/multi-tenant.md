# Multi-tenant context

> **Status:** Stable · **Config prefix:** `pulse.tenant` ·
> **Source:** [`io.github.arun0009.pulse.tenant`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/tenant) ·
> **Design notes:** [Multi-tenant context](../design/multi-tenant-context.md)

## Value prop

In a multi-tenant SaaS, *"slow checkout"* is meaningless without knowing
*"slow checkout for which tenant?"*. Pulse threads tenant identity through
the request without per-app glue, and (opt-in) bounds the resulting tag
cardinality independently of the global firewall.

## What it does

`TenantExtractor` is an SPI with three opt-in built-ins:

- **Header** — default `Pulse-Tenant-Id` (RFC 6648 — no `X-` prefix)
- **JWT claim** — configurable claim name on a Bearer token
- **Subdomain** — `acme.example.com` → tenant `acme`

Extraction priority is explicit: system property > header > JWT claim >
subdomain > `unknown`.

The resolved tenant lands on:

- **MDC** (`pulse.tenant.id`)
- **OTel baggage** (key `pulse.tenant.id`)
- **Every outbound HTTP / Kafka header** (configurable name)
- **(Opt-in) configured meters** as a tag

A separate `pulse.tenant.max-tag-cardinality` (default 100) caps the tenant
tag independently of the global firewall, so a runaway tenant never bucket-
spams your business meters.

## Configuration

```yaml
pulse:
  tenant:
    enabled: true
    inbound-header: Pulse-Tenant-Id
    outbound-header: Pulse-Tenant-Id
    default-tenant: unknown
    max-tag-cardinality: 100
    extractors:
      - header
      # - jwt
      # - subdomain
```

!!! note "Expanded coverage coming"

    Full reference (custom `TenantExtractor` SPI, JWT extractor config,
    metric tagging knobs) lands in a 1.0.x patch. The architectural
    rationale is already in
    [docs/design/multi-tenant-context.md](../design/multi-tenant-context.md).
