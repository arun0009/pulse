package io.github.arun0009.pulse.actuator;

import io.github.arun0009.pulse.async.AsyncProperties;
import io.github.arun0009.pulse.cache.CacheProperties;
import io.github.arun0009.pulse.container.ContainerMemoryProperties;
import io.github.arun0009.pulse.core.ContextProperties;
import io.github.arun0009.pulse.core.TraceGuardProperties;
import io.github.arun0009.pulse.db.DbProperties;
import io.github.arun0009.pulse.dependencies.DependenciesProperties;
import io.github.arun0009.pulse.events.WideEventsProperties;
import io.github.arun0009.pulse.exception.ExceptionHandlerProperties;
import io.github.arun0009.pulse.guardrails.CardinalityProperties;
import io.github.arun0009.pulse.guardrails.SamplingProperties;
import io.github.arun0009.pulse.guardrails.TimeoutBudgetProperties;
import io.github.arun0009.pulse.health.OtelExporterHealthProperties;
import io.github.arun0009.pulse.jobs.JobsProperties;
import io.github.arun0009.pulse.logging.LoggingProperties;
import io.github.arun0009.pulse.metrics.HistogramsProperties;
import io.github.arun0009.pulse.openfeature.OpenFeatureProperties;
import io.github.arun0009.pulse.priority.PriorityProperties;
import io.github.arun0009.pulse.profiling.ProfilingProperties;
import io.github.arun0009.pulse.propagation.KafkaPropagationProperties;
import io.github.arun0009.pulse.resilience.ResilienceProperties;
import io.github.arun0009.pulse.resilience.RetryProperties;
import io.github.arun0009.pulse.shutdown.ShutdownProperties;
import io.github.arun0009.pulse.slo.SloProperties;
import io.github.arun0009.pulse.startup.BannerProperties;
import io.github.arun0009.pulse.tenant.TenantProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/**
 * Test-only helper that materializes a {@link PulseDiagnostics.AllProperties} bundle with every
 * subsystem at its annotation-driven defaults — the same shape the runtime
 * {@code PulseAutoConfiguration#pulseAllProperties} bean produces against an empty environment.
 */
final class TestAllProperties {

    private TestAllProperties() {}

    static PulseDiagnostics.AllProperties bindEmpty() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        return new PulseDiagnostics.AllProperties(
                binder.bindOrCreate("pulse.context", Bindable.of(ContextProperties.class)),
                binder.bindOrCreate("pulse.trace-guard", Bindable.of(TraceGuardProperties.class)),
                binder.bindOrCreate("pulse.sampling", Bindable.of(SamplingProperties.class)),
                binder.bindOrCreate("pulse.async", Bindable.of(AsyncProperties.class)),
                binder.bindOrCreate("pulse.kafka", Bindable.of(KafkaPropagationProperties.class)),
                binder.bindOrCreate("pulse.exception-handler", Bindable.of(ExceptionHandlerProperties.class)),
                binder.bindOrCreate("pulse.cardinality", Bindable.of(CardinalityProperties.class)),
                binder.bindOrCreate("pulse.timeout-budget", Bindable.of(TimeoutBudgetProperties.class)),
                binder.bindOrCreate("pulse.wide-events", Bindable.of(WideEventsProperties.class)),
                binder.bindOrCreate("pulse.logging", Bindable.of(LoggingProperties.class)),
                binder.bindOrCreate("pulse.banner", Bindable.of(BannerProperties.class)),
                binder.bindOrCreate("pulse.histograms", Bindable.of(HistogramsProperties.class)),
                binder.bindOrCreate("pulse.slo", Bindable.of(SloProperties.class)),
                binder.bindOrCreate("pulse.health", Bindable.of(OtelExporterHealthProperties.class)),
                binder.bindOrCreate("pulse.shutdown", Bindable.of(ShutdownProperties.class)),
                binder.bindOrCreate("pulse.jobs", Bindable.of(JobsProperties.class)),
                binder.bindOrCreate("pulse.db", Bindable.of(DbProperties.class)),
                binder.bindOrCreate("pulse.resilience", Bindable.of(ResilienceProperties.class)),
                binder.bindOrCreate("pulse.profiling", Bindable.of(ProfilingProperties.class)),
                binder.bindOrCreate("pulse.dependencies", Bindable.of(DependenciesProperties.class)),
                binder.bindOrCreate("pulse.tenant", Bindable.of(TenantProperties.class)),
                binder.bindOrCreate("pulse.retry", Bindable.of(RetryProperties.class)),
                binder.bindOrCreate("pulse.priority", Bindable.of(PriorityProperties.class)),
                binder.bindOrCreate("pulse.container-memory", Bindable.of(ContainerMemoryProperties.class)),
                binder.bindOrCreate("pulse.open-feature", Bindable.of(OpenFeatureProperties.class)),
                binder.bindOrCreate("pulse.cache", Bindable.of(CacheProperties.class)));
    }
}
