# Pulse Production Readiness Checklist

Pulse defaults are tuned for safety — turn them down only with a reason. This checklist captures
what to verify before pointing real traffic at a Pulse-equipped service.

Each item is one of:
- **MUST** — verify before production cutover.
- **SHOULD** — strongly recommended; skip only with explicit owner sign-off.
- **NICE** — improves operability when traffic patterns warrant.

---

## Trace exporter

- **MUST** Set `OTEL_EXPORTER_OTLP_ENDPOINT` (or the per-signal variant) to your Collector.
  Without this, spans are dropped at the SDK boundary.
- **MUST** Verify exporter health is `UP` after a synthetic request:
  ```bash
  curl -s http://<host>/actuator/health | jq '.components.otelExporter'
  ```
- **SHOULD** Confirm the OTel Collector is configured for `tail_sampling` with at least an
  `error` policy — Pulse's in-process `prefer-sampling-on-error` is best-effort, not authoritative.
- **NICE** Set `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod,service.namespace=…` so
  multi-tenant Collectors can attribute spans correctly.

## Sampling

- **MUST** Set `management.tracing.sampling.probability` to a sustainable rate (Pulse defers to
  Spring Boot's standard property — it does not have its own knob). `1.0` is fine for low-traffic
  services; for >1k req/s reduce to `0.05`–`0.1` and rely on
  `pulse.sampling.prefer-sampling-on-error` (default `true`) to keep error spans.
- **SHOULD** Document the sampling rate in your service README so on-call understands trace coverage.

## Cardinality firewall

- **MUST** Leave `pulse.cardinality.enabled=true`.
- **SHOULD** Review `/actuator/pulse/runtime → cardinalityFirewall.topOffenders` after a soak test
  — anything in the list is a meter you should fix or explicitly exempt.
- **NICE** For services with very large meter inventories, set
  `pulse.cardinality.meter-prefixes-to-protect` to the high-risk subset rather than protecting
  everything.

## Timeout budget

- **MUST** Set `pulse.timeout-budget.default-budget` to your service's caller-side timeout minus
  ~50ms. Otherwise outbound calls have no notion of "we're already late."
- **SHOULD** Set `pulse.timeout-budget.maximum-budget` at the edge to prevent a malicious or
  buggy caller from sending `Pulse-Timeout-Ms: 86400000` and pinning a thread.
- **MUST** Subscribe to the `PulseTimeoutBudgetExhausted` alert and route to your on-call.

## Trace propagation guard

- **MUST** Confirm `pulse.trace.received` ratio is >0.99 in staging soak. Anything lower means an
  upstream is dropping context — fix it before production, not after.
- **NICE** Consider `pulse.trace-guard.fail-on-missing=true` in non-prod environments to catch
  regressions in CI.

## Logs

- **MUST** Verify your log layout includes `traceId`, `spanId`, `service`, `env`, `requestId`.
  The bundled `log4j2-spring.xml` does this; confirm it isn't being overridden.
- **MUST** Ship logs to a backend that lets you pivot from a span attribute to log lines —
  without this, a sampled trace has no log context.
- **SHOULD** Verify the PII masking converter is active by injecting a known-bad payload in a
  staging test (see `PiiMaskingConverterTest`).

## SLO & alerts

- **MUST** Declare at least one SLO under `pulse.slo.objectives` even if it's
  `availability=0.99`. The generated `PrometheusRule` is your incident-response contract.
- **MUST** Apply the generated rules:
  ```bash
  curl -s http://<host>/actuator/pulse/slo | kubectl apply -f -
  ```
- **MUST** Apply the standalone Pulse alerts (cardinality overflow, timeout exhausted, trace
  missing): `kubectl apply -f alerts/prometheus/pulse-slo-alerts.yaml`.
- **SHOULD** Wire the corresponding runbooks (`docs/runbooks/`) into the alert annotations so
  on-call has a one-click path from page to playbook.

## Shutdown

- **MUST** Leave `pulse.shutdown.otel-flush-enabled=true`. Without this, a rolling deploy drops
  the last few hundred spans before exit.
- **SHOULD** Confirm your container's `terminationGracePeriodSeconds` is at least
  `pulse.shutdown.otel-flush-timeout + 5s`; otherwise the orchestrator kills the pod before
  flush completes.

## Build metadata

- **SHOULD** Enable `spring-boot-maven-plugin`'s `build-info` goal so `BuildProperties` is
  available — this populates Pulse's `app.version` / `service.version` tags and the
  `pulse.deploy.info` gauge for deploy-overlay panels.
- **SHOULD** Generate `git.properties` (the same plugin can do this) so `build.commit` is tagged
  on every metric — invaluable for "did the deploy do this?" triage.

## Native image (if you ship native)

- **SHOULD** Run `mvn -Pnative test` in CI on every PR — Pulse's `RuntimeHints` cover the
  reflection it needs but app-specific reflection is on you.

## Smoke tests

- **MUST** A `curl /actuator/pulse` returns the expected subsystem map.
- **MUST** A `curl /actuator/pulse/slo` returns valid `PrometheusRule` YAML.
- **MUST** A request with `X-Trace-ID` returns the same id in the response header.
- **MUST** A request that throws produces a `ProblemDetail` with `traceId`, `requestId`, and
  `errorFingerprint` populated.
