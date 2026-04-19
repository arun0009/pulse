# Security policy

## Supported versions

Pulse follows SemVer. Security fixes are applied to the latest supported major line.

| Version | Status |
|---|---|
| `2.x` (latest) | ✅ Supported |
| Older majors   | ❌ Not supported — please upgrade |

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.**

Use GitHub's [private vulnerability reporting](https://github.com/arun0009/pulse/security/advisories/new)
to disclose privately. We aim to:

| Step | Target |
|---|---|
| First acknowledgement | within 72 hours |
| Triage decision (accept / decline / need info) | within 7 days |
| Patch release for accepted high/critical | within 30 days |
| Public disclosure | coordinated, after patch is available |

If GitHub's advisory flow is unavailable, email the maintainer listed in
[`pom.xml`](pom.xml) under `<developers>`.

## What's in scope

- Pulse code in this repository (`io.github.arun0009.pulse.*`).
- Defaults that increase the attack surface of an application using Pulse
	(e.g. unsafe header propagation, log injection, PII leakage).
- Anything in the published Maven Central artifact.

## What's out of scope

- Vulnerabilities in upstream dependencies (Spring Boot, OpenTelemetry,
	Micrometer, OkHttp, etc.) — please report to the respective project. We will
	bump versions promptly once they release a fix.
- The example failure-demo apps (`examples/`) — those are deliberately broken
	and not meant for production use.

## Hardening notes for operators

- The PII masking converter is a defence-in-depth layer, not a substitute for
	not logging secrets in the first place.
- Pulse does not cap the size of attribute values. Hostile upstream callers
	could inflate span/event payloads. Sample aggressively if your trust
	boundary requires it.
- The `X-Timeout-Ms` header can be user-controlled at your edge. Clamp inbound
	values with `pulse.timeout-budget.maximum-budget` to prevent unrealistic
	deadlines from propagating through your call graph.

Thank you for helping keep Pulse and its users safe.
