package io.github.arun0009.pulse.startup.internal;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Loud, fail-soft startup signal that Pulse is a <strong>Servlet-only</strong> starter.
 *
 * <p>Pulse 2.x is intentionally Servlet-only — every inbound filter
 * ({@link io.github.arun0009.pulse.core.PulseRequestContextFilter},
 * {@link io.github.arun0009.pulse.guardrails.TimeoutBudgetFilter},
 * {@link io.github.arun0009.pulse.core.TraceGuardFilter},
 * {@link io.github.arun0009.pulse.tenant.TenantContextFilter},
 * {@link io.github.arun0009.pulse.priority.RequestPriorityFilter},
 * {@link io.github.arun0009.pulse.resilience.RetryDepthFilter},
 * {@link io.github.arun0009.pulse.dependencies.RequestFanoutFilter},
 * {@link io.github.arun0009.pulse.shutdown.InflightRequestCounter})
 * extends {@link org.springframework.web.filter.OncePerRequestFilter} or implements
 * {@link jakarta.servlet.Filter} directly. None of them activate on the reactive (WebFlux) stack.
 *
 * <p>This post-processor inspects {@link SpringApplication#getWebApplicationType()} <em>before</em>
 * any beans are wired and emits one of three signals:
 *
 * <ul>
 *   <li><b>SERVLET</b> — silent. The default, expected case.
 *   <li><b>NONE</b> — silent. Non-web worker / batch / CLI apps still benefit from the cardinality
 *       firewall, MDC propagation across {@code @Async}/{@code @Scheduled}, and Kafka propagation.
 *   <li><b>REACTIVE</b> — emit a single, scannable WARN linking to {@code docs/web-stack.md} so a
 *       developer who picked the wrong starter sees it on the first boot rather than after a long
 *       debugging session about "why don't my Pulse filters fire".
 * </ul>
 *
 * <p>The warning never fails startup — it is purely diagnostic. A consumer who genuinely wants to
 * use Pulse for its non-filter beans on a reactive app can silence the warning by setting
 * {@code pulse.web-stack.suppress-reactive-warning=true}.
 */
@NullMarked
public final class PulseWebStackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger("pulse.startup");

    static final String SUPPRESS_PROPERTY = "pulse.web-stack.suppress-reactive-warning";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        WebApplicationType type = application.getWebApplicationType();
        if (type != WebApplicationType.REACTIVE) {
            return;
        }
        if (Boolean.parseBoolean(environment.getProperty(SUPPRESS_PROPERTY, "false"))) {
            return;
        }
        log.warn("""

                ────────────────────────────────────────────────────────────────────────────────
                  PULSE web-stack mismatch
                ────────────────────────────────────────────────────────────────────────────────
                  This application starts as REACTIVE (spring-boot-starter-webflux) but Pulse
                  2.x is a SERVLET-only observability starter. Every inbound filter Pulse
                  ships (request-context, timeout-budget, trace-guard, tenant, priority,
                  retry-depth, fan-out, inflight-counter) extends OncePerRequestFilter and
                  will NOT fire on the reactive pipeline.

                  Non-filter beans (cardinality firewall, async/scheduled MDC propagation,
                  Kafka propagation, OpenTelemetry sampler, SLO rule generator) still work
                  on reactive apps, but the bulk of Pulse's value depends on the servlet
                  request lifecycle.

                  Either:
                    • switch to spring-boot-starter-web, or
                    • silence this warning with: pulse.web-stack.suppress-reactive-warning=true

                  See https://arun0009.github.io/pulse/web-stack/ for the full rationale.
                ────────────────────────────────────────────────────────────────────────────────
                """);
    }
}
