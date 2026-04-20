# Quick start

Three steps. No agent. No bytecode weaving. No custom runtime.

## 1. Add the dependency

Pulse is on
[Maven Central](https://central.sonatype.com/artifact/io.github.arun0009/pulse-spring-boot-starter).

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.github.arun0009</groupId>
      <artifactId>pulse-spring-boot-starter</artifactId>
      <version>2.0.0</version>
    </dependency>
    ```

=== "Gradle (Kotlin DSL)"

    ```kotlin
    implementation("io.github.arun0009:pulse-spring-boot-starter:2.0.0")
    ```

=== "Gradle (Groovy DSL)"

    ```groovy
    implementation 'io.github.arun0009:pulse-spring-boot-starter:2.0.0'
    ```

??? note "Logback users — opt out of Log4j2"

    Pulse defaults to Log4j2 (Spring Boot's higher-throughput logging
    backend). To use Logback instead:

    ```xml
    <dependency>
      <groupId>io.github.arun0009</groupId>
      <artifactId>pulse-spring-boot-starter</artifactId>
      <exclusions>
        <exclusion>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-starter-log4j2</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-logging</artifactId>
    </dependency>
    ```

    Pulse's `logback-spring.xml` produces the **exact same** JSON shape as
    the Log4j2 path. Same field set, same PII masking, same resource
    attributes. Dashboards work unchanged.

## 2. Point at your OTel Collector

Skip this if you already have OTLP env vars set:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

??? tip "Don't have a Collector yet?"

    Pulse ships a single-command local stack — Collector, Prometheus,
    Grafana, Jaeger, and Loki, all wired up:

    ```bash
    docker compose -f deploy/local-stack/docker-compose.yml up -d
    ```

    Details on the [local stack page](operations/dashboards.md).

## 3. Start your app and verify

The exact command depends on your build (`./mvnw spring-boot:run`,
`./gradlew bootRun`, etc.). Once it's up, ask the actuator whether spans are
landing — substitute your management port (default `8080`):

```bash
$ curl -s localhost:8080/actuator/health/otelExporter
{"status":"UP","details":{"lastSuccessAgeMs":1230,"totalSuccess":14}}
```

That's it. Every Pulse feature is on by default with sensible production
defaults, and the diagnostic UI tells you exactly what's running.

## What now?

<div class="grid cards" markdown>

-   :material-eye-outline:{ .lg .middle } **See what's running**

    ---

    Open `/actuator/pulseui` in your browser for a single-page summary of
    every Pulse feature — what's on, what's off, which configuration won.

-   :material-school-outline:{ .lg .middle } **Learn the model**

    ---

    [Concepts](concepts.md) explains how Pulse threads context through
    Spring, OTel, MDC, baggage, and outbound clients.

-   :material-view-grid-outline:{ .lg .middle } **Browse features**

    ---

    The [feature catalogue](features/index.md) groups all 25 features by
    use case, with config keys and links to runbooks.

-   :material-tune-vertical:{ .lg .middle } **Tune for production**

    ---

    The [configuration reference](configuration.md) covers sampling,
    budgets, and SLO objectives. The [production
    checklist](operations/production-checklist.md) is the cutover bar.

</div>
