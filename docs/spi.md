# Extending Pulse: SPIs and bean overrides

Pulse 2.x exposes a small set of well-defined SPIs (Service Provider Interfaces)
for the parts of observability that are most platform-, organization-, or
domain-specific: dependency tagging, error fingerprinting, host detection,
tenant resolution, MDC contributions, and request gating. Every SPI follows
the same two patterns:

1. **Composition over replacement** — multi-instance SPIs use a
   *chain-of-responsibility* (one bean per concern, ordered by `@Order`,
   first non-null wins). You add behavior by registering an additional bean,
   not by replacing Pulse's defaults.
2. **`@ConditionalOnMissingBean` on override points** — most user-replaceable
   `@Bean` definitions defer to your bean of the same type (or a well-known
   bean name for chain terminals — see below). Ordered chain composites remain
   registered unless you supply the named replacement bean.

This page lists every SPI, what it controls, when to reach for it, and a
copy-paste example. It is not the *only* extension surface — every
override-worthy Pulse `@Bean` is documented in the per-feature pages — but it
is the surface intended to be extended *frequently*.

---

## SPI catalog

| SPI                          | Package                                | Multi-instance? | Activates                                       |
| ---------------------------- | -------------------------------------- | --------------- | ----------------------------------------------- |
| `DependencyClassifier`       | `io.github.arun0009.pulse.dependencies` | yes (chain)     | `pulse.dependencies.enabled=true` (default)     |
| `ErrorFingerprintStrategy`   | `io.github.arun0009.pulse.exception`    | yes (chain)     | always                                          |
| `HostNameProvider`           | `io.github.arun0009.pulse.logging`      | no              | startup (EPP) + runtime bean                    |
| `ResourceAttributeResolver`  | `io.github.arun0009.pulse.logging`      | no              | startup (EPP) + runtime bean                    |
| `TenantExtractor`            | `io.github.arun0009.pulse.tenant`       | yes (ordered)   | `pulse.tenant.enabled=true` (default)           |
| `ContextContributor`         | `io.github.arun0009.pulse.core`         | yes (additive)  | every inbound request                           |
| `PulseRequestMatcher`        | `io.github.arun0009.pulse.core`         | per feature     | any feature with an `enabled-when:` block       |

> **Outside this list?** Look for the matching `@Bean` in the auto-config — if
> it has `@ConditionalOnMissingBean`, you can override it the same way you
> override any Spring Boot starter bean. If it does not, that is intentional
> (additive customizers, BeanPostProcessors, ordered chain terminals); open an
> issue if you need an additional override point.

---

## DependencyClassifier — tag outbound calls

Maps an outbound URI (or raw host) to the logical `dep` tag stamped on every
`pulse.dependency.*` meter. The default link is the
`pulse.dependencies.map` (host → logical name); user beans participate in
an ordered chain and the first non-null result wins.

**Reach for it when** Pulse's host-table cannot tag a destination correctly —
for example, when the same host serves multiple logical APIs distinguished by
URL path (`/api/v1/payments` vs `/api/v1/inventory`), or when you proxy through
a single egress gateway.

```java
@Bean
@Order(0) // run before the host-table terminal link
DependencyClassifier paymentApiClassifier() {
    return new DependencyClassifier() {
        @Override public String classify(URI uri) {
            String path = uri.getPath();
            if (path != null && path.startsWith("/api/v1/payments/")) {
                return "payment-api-v1";
            }
            return null; // delegate to the next link
        }

        @Override public String classifyHost(String host) {
            return null; // host-only callers (Kafka, OkHttp) keep using the host table
        }
    };
}
```

**Contract.** Implementations must be cheap, thread-safe, and never throw —
return `null` to delegate, never `""`. The terminal link
(`pulseDependencyHostTableClassifier`) always returns a non-null value, so the
composite never returns `null` to downstream callers.

---

## ErrorFingerprintStrategy — group errors by your taxonomy

Computes a stable fingerprint for a `Throwable` so the
`pulse_exceptions_total{fingerprint=...}` counter and RFC 7807 problem details
group errors usefully. The default link is `ErrorFingerprintStrategy.DEFAULT`
(SHA-256 of the exception class name + first stack frame). User beans
participate in an ordered chain and the first non-null result wins.

**Reach for it when** your team already has an error taxonomy (incident codes,
runbook IDs) you want appearing on dashboards instead of opaque hashes.

```java
@Bean
@Order(0)
ErrorFingerprintStrategy domainErrorFingerprints() {
    return throwable -> switch (throwable) {
        case PaymentDeclinedException ignored -> "payment-declined";
        case TenantSuspendedException ignored -> "tenant-suspended";
        default -> null; // delegate to the SHA-256 default
    };
}
```

**Contract.** Must never throw. Returning `null` lets the next link try; the
terminal default always produces a non-null fingerprint.

---

## HostNameProvider — replace `InetAddress.getLocalHost()`

Resolves the local host name used to populate the `host.name` OTel resource
attribute stamped on every Pulse log line.

**Reach for it when** `InetAddress.getLocalHost()` is unreliable on your
platform — common on minimal Alpine images that return `"localhost"`, on
hardened JVMs that throw `UnknownHostException`, or on systems where you
already have a more authoritative source (the EC2 metadata service, the K8s
downward API written to a file).

Because the host name is needed *before* any Spring bean exists (it is seeded
as a JVM system property at `EnvironmentPostProcessor` time so the JSON
layout can substitute it), Pulse discovers `HostNameProvider` implementations
through Spring's `spring.factories` mechanism *and* through the application
context.

```java
// 1. Implement the SPI.
public final class Ec2MetadataHostNameProvider implements HostNameProvider {
    @Override public String localHostName() { /* IMDS call */ }
}

// 2. Register for early startup (EPP time).
//    src/main/resources/META-INF/spring.factories
io.github.arun0009.pulse.logging.HostNameProvider=\
    com.acme.observability.Ec2MetadataHostNameProvider

// 3. (Optional) Also publish as a Spring bean so runtime code uses the same impl.
@Bean
HostNameProvider ec2HostNameProvider() {
    return new Ec2MetadataHostNameProvider();
}
```

The runtime `HostNameProvider` bean is `@ConditionalOnMissingBean`, so step 3
replaces Pulse's default `InetAddress.getLocalHost()` wrapper.

---

## ResourceAttributeResolver — extend resource detection

Resolves OTel resource attributes (`host.name`, `container.id`, `k8s.*`,
`cloud.*`) once at startup. Pulse 2.0 opens this class for subclassing so you
can add custom attributes (`deployment.id`, `instance.type`, `cell.name`) or
replace specific detections.

**Reach for it when** you need attributes Pulse does not detect natively, or
when your platform exposes the same value through a more reliable source
than Pulse's defaults.

```java
public final class AcmeResourceAttributeResolver extends ResourceAttributeResolver {

    public AcmeResourceAttributeResolver() {
        super(); // uses InetAddress.getLocalHost() for host.name
    }

    /** Add custom attributes alongside the built-in OTel semconv keys. */
    @Override
    protected Map<String, String> contributeAdditional() {
        return Map.of(
            "deployment.id", System.getenv("DEPLOY_ID"),
            "instance.type", System.getenv("INSTANCE_TYPE"));
    }
}
```

```properties
# src/main/resources/META-INF/spring.factories
io.github.arun0009.pulse.logging.ResourceAttributeResolver=\
    com.acme.observability.AcmeResourceAttributeResolver
```

User-contributed keys are merged into `resolveAll()`. **Built-in OTel semconv
keys win on collision** so dashboards keep their canonical shape — if you
need to override a built-in key, override the matching protected accessor
(`hostName()`, `kubernetesNamespace()`, `detectCloud()`, …) instead.

The runtime `ResourceAttributeResolver` Spring bean is `@ConditionalOnMissingBean`;
publish your subclass as a `@Bean` to keep the EPP-time view and runtime view
consistent.

---

## TenantExtractor — resolve tenant identity

Resolves the tenant ID for an inbound HTTP request. Pulse ships three built-in
extractors (header, JWT claim, subdomain), each opt-in via its own
`pulse.tenant.*.enabled` property; user beans participate in the same
`@Order`-sorted chain.

**Reach for it when** none of the built-in sources matches your tenant
encoding scheme (custom header format, body field, mTLS client certificate
SAN, OAuth scope claim with structured payload).

```java
@Bean
@Order(0) // run before the built-in extractors
TenantExtractor mtlsTenantExtractor() {
    return request -> Optional
        .ofNullable((X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate"))
        .filter(certs -> certs.length > 0)
        .map(certs -> certs[0].getSubjectX500Principal().getName())
        .map(TenantExtractor::ouFromDn);
}
```

**Contract.** Return `Optional.empty()` to delegate; never a placeholder
string. Implementations must be inexpensive (called on every request) and
must not throw.

---

## ContextContributor — add MDC keys to every request

Adds service-specific MDC keys to every inbound request after Pulse's standard
keys (`traceId`, `requestId`, `userId`, `tenantId`, …) have been set.

**Reach for it when** there is a piece of context that should appear on
**every** log line for your service: region, cell, deployment color, A/B
bucket, feature-flag treatment.

```java
@Component
class RegionContributor implements ContextContributor {
    @Override
    public void contribute(HttpServletRequest request) {
        String region = request.getHeader("X-Region");
        if (region != null) MDC.put("region", region);
    }
}
```

**Contract.** Do **not** call `MDC.clear()` — Pulse owns the per-request MDC
lifecycle. Multiple `ContextContributor` beans can coexist; Pulse invokes
each one once per request.

---

## PulseRequestMatcher — declarative or programmatic feature gating

Predicate that decides whether a Pulse feature should run for a given inbound
request. Used as the runtime form of the `enabled-when:` configuration block
on every Pulse feature that supports gating (trace-guard, cardinality
firewall, dependency fan-out, …).

You almost always want the YAML form ([Conditional features](features/conditional-features.md)).
Reach for the SPI when your gating logic does not fit the declarative
matcher's header/path criteria — e.g. it depends on tenant-tier metadata, an
LD flag evaluated per request, or a runtime signal that is not on the request.

```java
@Bean("rolloutGate") // referenced by enabled-when.bean: rolloutGate
PulseRequestMatcher rolloutGate(FeatureFlagClient flags) {
    return request -> flags.isEnabled("pulse.cardinality.canary",
        request.getHeader("X-User-Id"));
}
```

```yaml
pulse:
  cardinality:
    enabled-when:
      bean: rolloutGate
```

**Contract.** Must be **side-effect-free**, must not consult mutable state,
and must not throw. Heavy work belongs in the bean's constructor; `matches`
is invoked on every request before any feature work.

---

## Overriding any other Pulse `@Bean`

Every override-worthy `@Bean` in Pulse's auto-configs is gated on
`@ConditionalOnMissingBean`. The standard Spring Boot pattern works
unchanged: declare a bean of the same name (or, for type-only beans, the
same type) in your `@Configuration` and Pulse stops registering its own.

Notable override points (see `PulseBeanOverrideContractTest` for the pinned
list):

| Bean name                                  | What you replace                                        |
| ------------------------------------------ | ------------------------------------------------------- |
| `pulseCommonTags`                            | Common tags applied to every meter.                     |
| `pulseRequestFanoutFilterRegistration`       | The fan-out filter registration (filter + URL pattern). |
| `pulseTenantContextFilter`                   | The inbound tenant-context filter registration.         |
| `pulseRetryDepthFilter`                      | The inbound retry-depth filter registration.            |
| `pulseHostNameProvider`                      | Default host name provider (also see `HostNameProvider` SPI above). |
| `pulseResourceAttributeResolver`             | Default resource-attribute resolver bean.               |
| `pulseDependencyResolver`                    | Default host-table resolver (also a `DependencyClassifier`). |
| `pulseOkHttpInterceptor`                     | Stand-alone OkHttp interceptor (additive `BeanPostProcessor` is separate). |
| `pulseKafkaRecordInterceptor`                | Pulse's Kafka inbound record interceptor.               |
| `pulseAllProperties`                         | The aggregated properties snapshot exposed by the actuator. |
| `pulseRestTemplateCustomizer`                | MDC-header propagation for `RestTemplate`.              |
| `pulseTimeoutBudgetRestTemplateCustomizer`   | Timeout-budget propagation for `RestTemplate`.          |
| `pulseRestClientCustomizer`                  | MDC-header propagation for `RestClient`.                |
| `pulseTimeoutBudgetRestClientCustomizer`     | Timeout-budget propagation for `RestClient`.            |
| `pulseWebClientCustomizer`                   | MDC-header + timeout-budget propagation for `WebClient`.|
| `pulseOkHttpBuilderInstrumenter`             | `BeanPostProcessor` that instruments every `OkHttpClient.Builder`. |
| `pulseDependencyRestTemplateCustomizer`      | Dependency-tagging interceptor for `RestTemplate`.      |
| `pulseDependencyRestClientCustomizer`        | Dependency-tagging interceptor for `RestClient`.        |
| `pulseDependencyWebClientCustomizer`         | Dependency-tagging filter for `WebClient`.              |
| `pulseDependencyOkHttpInstrumenter`          | `BeanPostProcessor` that installs the dependency OkHttp interceptor. |
| `pulseKafkaPropagationContextInitializer`    | Bean that seeds `KafkaPropagationContext` at startup.   |
| `pulseProducerFactoryCustomizer`             | `BeanPostProcessor` that appends Pulse's producer interceptor. |
| `pulseRecordInterceptorComposite`            | `@Primary RecordInterceptor` that composes user interceptors with Pulse's. |

> **Chain composites (advanced replacement).** Pulse registers `@Primary`
> composites `pulseDependencyClassifier` and `pulseErrorFingerprintStrategy`
> unless you define your **own** beans with those exact names — in that case
> Pulse skips its composite and wires yours instead (escape hatch for a fully
> custom implementation). Terminal links (`pulseDependencyHostTableClassifier`,
> `pulseDefaultErrorFingerprintStrategy`) remain; add more links via ordinary
> ordered SPI beans instead of replacing terminals when possible.
>
> Toggle-gated `BeanPostProcessor`s like `pulsePreferErrorSamplerWrapper` are
> disabled via `pulse.sampling.prefer-sampling-on-error=false`, not by bean
> replacement.
