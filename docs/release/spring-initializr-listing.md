# Spring Initializr listing

Pulse is a Spring Boot starter, so it should be selectable from
[start.spring.io](https://start.spring.io). This page captures the exact PR
we send to [`spring-io/start.spring.io`](https://github.com/spring-io/start.spring.io)
to add Pulse as a community dependency.

## Why list on Initializr

* **Discovery.** Most Spring Boot users start a service from Initializr.
  Showing up in the dependency picker is how greenfield apps adopt Pulse.
* **Consistency.** The listing pins the artifact group/id so apps don't
  copy-paste a stale version.
* **No-friction adoption.** Tick a box, get an app with Pulse already on
  the classpath and the auto-configuration wired in.

## YAML to add to `start.spring.io`

The Initializr config lives in
[`start-site/src/main/resources/application.yml`](https://github.com/spring-io/start.spring.io/blob/main/start-site/src/main/resources/application.yml)
under `initializr.dependencies`. Add Pulse to the **Observability** group:

```yaml
- name: Observability
  content:
    - name: Pulse (production-correctness starter)
      id: pulse
      groupId: io.github.arun0009
      artifactId: pulse-spring-boot-starter
      description: >-
        Cardinality firewall, timeout-budget propagation, wide-event API,
        structured logging, and trace-correlated MDC — pre-installed for
        Spring Boot.
      starter: true
      links:
        - rel: reference
          href: https://arun0009.github.io/pulse/
          description: Pulse documentation
        - rel: guide
          href: https://arun0009.github.io/pulse/quickstart/
          description: 5-minute quickstart
      compatibilityRange: "[3.2.0,4.1.0-M1)"
```

## Submission checklist

Before opening the PR upstream:

- [ ] **Release a stable version** (no `-SNAPSHOT`) to Maven Central. Initializr
      will not list a pre-release-only artifact.
- [ ] **Verify the artifact resolves** with the `compatibilityRange` declared
      above. Run a smoke build with the lowest supported Spring Boot version
      and the highest.
- [ ] **Confirm `pulse-spring-boot-starter` is the correct module.** It must
      be the artifact that pulls in everything via auto-configuration.
- [ ] **Add a one-line release note** linking to the Initializr PR for the
      version that ships with the listing.

## PR template (to use upstream)

> **Title:** Add Pulse (community starter) to the Observability category
>
> **Body:**
> Adds [Pulse](https://github.com/arun0009/pulse), a small Spring Boot
> starter for production correctness — cardinality firewall, timeout-budget
> propagation, wide events, structured logging, MDC. Documentation:
> <https://arun0009.github.io/pulse/>. Maven Central:
> `io.github.arun0009:pulse-spring-boot-starter`.
>
> The starter is already auto-configuration-wired and supports Spring Boot
> 3.2 → 4.0. License is MIT.
