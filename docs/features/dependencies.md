# Dependency health map

> **TL;DR.** Caller-side RED metrics per downstream + a health indicator
> that flips DEGRADED when error rates spike. One PromQL query answers
> *"which downstream is killing me?"*.

When the system is misbehaving, the question is *"which downstream is killing
me?"* — and the answer is buried across fifty dashboards. Server-side metrics
on the downstream don't capture caller-side retries, circuit-breaker fallbacks,
or pool saturation. Caller-side metrics do.

**Pulse records caller-side RED metrics for every outbound call.** One PromQL
query per service tells you which downstream is responsible for the pain.

## What you get

```promql
topk(5, sum by (dependency)
        (rate(pulse_dependency_requests_total{status_class!="2xx"}[5m])))
```

The five worst-offender downstreams, ranked by error rate. Pivots straight to
the `pulse_dependency_latency_seconds` histogram for the same `dependency`
to see whether they're slow, broken, or both.

A health indicator (`/actuator/health/dependency`) flips DEGRADED when any
critical dependency's caller-side error rate crosses
`pulse.dependencies.health.error-rate-threshold` — so Kubernetes pulls the
pod out of rotation before users see it.

## Turn it on

Nothing. On by default. Pulse classifies dependencies by host name; for
nicer logical names, annotate the client:

```java
@PulseDependency("payment-service")
RestClient paymentClient = RestClient.create();
```

## What it adds

| Metric | Tags | Meaning |
| --- | --- | --- |
| `pulse.dependency.requests` | `dependency`, `status_class` | RED count, caller-side |
| `pulse.dependency.latency` | `dependency`, `status_class` | RED latency, caller-side |
| `pulse.request.fan_out` | `endpoint` | Distinct dependencies called per inbound request — see [Request fan-out](fan-out.md) |

## When to skip it

You usually don't. The metrics are emitted by the same outbound interceptors
that handle [timeout-budget propagation](timeout-budget.md), so the
incremental cost is one Micrometer record per call.

To turn off just the health indicator (keep the metrics):

```yaml
pulse:
  dependencies:
    health:
      enabled: false
```

## Conditional gating

`pulse.dependencies.enabled-when` controls both the per-call RED metrics
*and* the [request fan-out counter](fan-out.md). Use it to suppress
metric emission for synthetic probes:

```yaml
pulse:
  dependencies:
    enabled-when:
      header-not-equals:
        x-pulse-synthetic: "true"
```

For background traffic (scheduled jobs, Kafka consumers) where no inbound
request is bound to the thread, Pulse fails open and still records the
outbound call — you don't lose visibility on jobs.

## Custom dependency classification (`DependencyClassifier`)

The host-table strategy described above (`pulse.dependencies.map`) covers
most cases. When it doesn't — wildcard regions, URL-path-aware naming,
gateway-stamped headers — drop in a `DependencyClassifier` bean.

Since 2.0 the SPI is **chain-of-responsibility**: every
`DependencyClassifier` bean in the context becomes one link, ordered by
`@Order` (lower runs first), and the first one that returns a non-`null`
value wins. The built-in host-table resolver is registered as the terminal
link, so any input the chain doesn't recognise still falls back to your
configured `pulse.dependencies.default-name`.

That means custom classifiers stay *additive* — handle the cases you care
about and return `null` to delegate:

```java
@Bean
@Order(0)
DependencyClassifier paymentApiClassifier() {
    return new DependencyClassifier() {
        @Override public String classify(URI uri) {
            if (uri.getPath() != null && uri.getPath().startsWith("/api/v1/payments/")) {
                return "payment-api-v1";
            }
            return null; // delegate to the next link
        }
        @Override public String classifyHost(String host) {
            return null;
        }
    };
}
```

Pulse routes every transport (RestTemplate, RestClient, WebClient, OkHttp,
Apache HttpClient 5, Kafka) through the chain. Implementations must be
cheap, thread-safe, and must never throw — return `null` on doubt so the
next link gets a chance and cardinality stays bounded.

---

**Source:** [`io.github.arun0009.pulse.dependencies`](https://github.com/arun0009/pulse/tree/main/src/main/java/io/github/arun0009/pulse/dependencies) ·
**Status:** Stable since 1.0.0
