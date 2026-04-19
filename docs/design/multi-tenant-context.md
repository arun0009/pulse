# Multi-tenant context — design notes

> Status: shipped in 1.0. Implementation lives under `io.github.arun0009.pulse.tenant`.

## Problem

A multi-tenant Spring service today has to hand-roll, per app:

1. A way to extract the tenant from the inbound request (header, JWT claim, subdomain).
2. A way to put it on every log line.
3. A way to propagate it across `@Async`, `@Scheduled`, RestTemplate / RestClient / WebClient /
   OkHttp, and Kafka so downstream services see the same tenant.
4. A way to tag metrics with it without blowing up Prometheus on a 10k-tenant SaaS.

Every team builds the same plumbing slightly differently. There is no turnkey Spring Boot
library. Pulse is the right place to add it because it already owns the propagation chain
(timeout budgets, idempotency keys, request id) and the cardinality firewall.

## Non-goals

- Tenant-aware data partitioning, schema-per-tenant, or row-level security. That belongs in
  Hibernate and the application's persistence layer, not in observability.
- Tenant authorization. The Spring Security ecosystem owns that. Pulse only attributes signals
  to the tenant the application has already identified.

## API surface

### `TenantExtractor` SPI

```java
@FunctionalInterface
public interface TenantExtractor {
    Optional<String> extract(HttpServletRequest request);
}
```

- One method.
- Returns `Optional<String>` — raw string. Tier, region, and other per-tenant metadata are
  separate baggage keys; conflating them with identity would force every implementation to
  understand the full tenant model.
- No "strategy chain" abstraction. Spring already gives ordered injection of all
  `TenantExtractor` beans via `@Order`; the resolver just walks the list.

Pulse ships three built-in implementations, each opt-in via `@ConditionalOnProperty`:

| Bean | Property to enable | Default order |
|---|---|---|
| `HeaderTenantExtractor` (`Pulse-Tenant-Id`) | `pulse.tenant.header.enabled=true` (default) | 100 |
| `JwtClaimTenantExtractor` (`tenant_id` claim) | `pulse.tenant.jwt.enabled=true` | 200 |
| `SubdomainTenantExtractor` | `pulse.tenant.subdomain.enabled=false` | 300 |

User overrides any of them by declaring their own `@Bean TenantExtractor`. Spring's bean
precedence handles replacement.

### Extraction priority

Resolved at request time by `TenantContextResolver`:

1. `pulse.tenant.id` system property (test/dev override only).
2. Highest-priority extractor whose `extract()` returns non-empty.
3. Falls back to `null` (recorded as MDC `unknown` and metric tag `unknown`).

First non-empty wins. The system property at the top is mainly for `@SpringBootTest`
ergonomics; in production the header/JWT extractors dominate.

## Cardinality

Tenant ID is the single highest-cardinality dimension a multi-tenant app emits. It deserves a
dedicated knob, not a piggyback on the global firewall:

- `pulse.tenant.max-tag-cardinality` (default `100`) — separate cap, applied via the existing
  `CardinalityFirewall` machinery as a meter-prefix-scoped override.
- Overflow value: `__overflow__` (matches the firewall's existing convention).
- Documentation will tell operators: "if you have ≤100 tenants, set this to your tenant
  count; if you have more, decide explicitly which meters carry the `tenant` tag using
  `pulse.tenant.tag-meters`."

## Propagation scope

Default posture: **tenant goes everywhere except metrics.**

| Channel | Default | Configurable |
|---|---|---|
| MDC (every log line) | always | no |
| OTel baggage (cross-service) | always | no |
| Outbound HTTP headers (`Pulse-Tenant-Id`) | always | no |
| Kafka producer headers | always | no |
| Tag on `pulse.events` (wide-events counter) | always | no |
| Tag on `http.server.requests`, `pulse.dependency.*`, etc. | **off** | `pulse.tenant.tag-meters` (list) |

The opt-in list lets an operator say "I have 30 tenants, I want full per-tenant RED on HTTP
and dependency metrics" by setting:

```yaml
pulse:
  tenant:
    tag-meters:
      - http.server.requests
      - pulse.dependency.requests
      - pulse.dependency.latency
```

The cardinality firewall then enforces `max-tag-cardinality` on every meter in this list, so
the operator can flip the switch without auditing the entire metric tree.

`pulse.events` always carries the `tenant` tag because attributing business events to the
tenant is the whole point of having tenants.

## Storage on the request

- A static `TenantContext` (mirroring `TimeoutBudget`) exposes `current() -> Optional<String>`
  to user code.
- Backed by OTel baggage (so context propagates across `@Async` and reactive Reactor Context
  via the existing Pulse propagation machinery without duplicate plumbing).
- MDC is set by the inbound filter and cleared on completion, same lifecycle as `requestId`.

## Filter ordering

Inbound filter chain (after this change):

1. `MdcFilter` — request id, correlation id, user id (existing)
2. `TenantContextFilter` — runs extractors, sets MDC + baggage, registers Micrometer common tag
3. `TraceGuardFilter` (existing)
4. `TimeoutBudgetFilter` (existing)
5. `RequestFanoutFilter` (added in 0.3.0)

Tenant must come after MDC (so MDC is populated) and before TraceGuard / TimeoutBudget
(those rely on the tenant context being on baggage when they emit events).

## Things explicitly excluded from 0.3.0

- Per-tenant rate limits / quotas (application concern).
- Tenant tier-aware load shedding (would need request priority — deferred to 0.4.0).
- `tenant.region` / `tenant.tier` baggage keys (can be added later as separate
  `TenantMetadataExtractor` SPI without breaking the identity API).
