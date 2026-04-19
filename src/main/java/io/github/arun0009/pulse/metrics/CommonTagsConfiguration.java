package io.github.arun0009.pulse.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Tags every meter with the identifiers operators reach for first when triaging an incident:
 * service name, environment, application version, and (when available) the source-control commit
 * the build came from.
 *
 * <p>Pulse emits <em>both</em> the legacy Micrometer-style tags ({@code application}, {@code env})
 * <em>and</em> the OpenTelemetry semantic-convention equivalents ({@code service.name},
 * {@code deployment.environment}). Boot's OTel starter already adds the OTel ones to traces; this
 * makes them available on metrics too without breaking dashboards keyed on the legacy names. The
 * legacy tags are deprecated and will be removed in Pulse 2.0 — see the migration note in the
 * release notes.
 *
 * <p>If {@link BuildProperties} or {@link GitProperties} beans are present (Spring Boot adds them
 * when {@code spring-boot-maven-plugin}'s {@code build-info} goal runs and when {@code git.properties}
 * is on the classpath, respectively), Pulse adds {@code app.version} and {@code build.commit}
 * tags. These let dashboards overlay deploys on incidents — the single most-asked-for SRE feature.
 */
@Configuration(proxyBeanMethods = false)
public class CommonTagsConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> pulseCommonTags(
            @Value("${spring.application.name:unknown-service}") String serviceName,
            @Value("${app.env:unknown-env}") String environment,
            ObjectProvider<BuildProperties> buildPropertiesProvider,
            ObjectProvider<GitProperties> gitPropertiesProvider) {

        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        GitProperties gitProperties = gitPropertiesProvider.getIfAvailable();

        List<Tag> tags = new ArrayList<>();
        // Legacy Micrometer tags — kept for backward compatibility with dashboards written
        // against earlier Pulse versions. Deprecated; will be removed in Pulse 2.0.
        tags.add(Tag.of("application", serviceName));
        tags.add(Tag.of("env", environment));
        // OpenTelemetry semantic conventions — the forward-compatible names.
        tags.add(Tag.of("service.name", serviceName));
        tags.add(Tag.of("deployment.environment", environment));

        String version = resolveVersion(buildProperties);
        if (version != null) {
            tags.add(Tag.of("app.version", version));
            tags.add(Tag.of("service.version", version));
        }
        String commit = resolveCommit(gitProperties);
        if (commit != null) {
            tags.add(Tag.of("build.commit", commit));
        }

        return registry -> registry.config().commonTags(tags);
    }

    private static @Nullable String resolveVersion(@Nullable BuildProperties buildProperties) {
        if (buildProperties == null) return null;
        String v = buildProperties.getVersion();
        return (v == null || v.isBlank()) ? null : v;
    }

    private static @Nullable String resolveCommit(@Nullable GitProperties gitProperties) {
        if (gitProperties == null) return null;
        String shortId = gitProperties.getShortCommitId();
        if (shortId != null && !shortId.isBlank()) return shortId;
        String full = gitProperties.getCommitId();
        return (full == null || full.isBlank()) ? null : full;
    }
}
