package io.github.arun0009.pulse.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits a single {@code pulse.deploy.info} gauge whose value is the deploy timestamp (epoch
 * seconds) and whose tags carry version + commit metadata. Dashboards can overlay deploys onto
 * incident timelines using this meter as a marker source — answering "did we ship something
 * right before the alert fired?" in one panel.
 *
 * <p>The gauge is intentionally a one-time-set value (not a counter) so it appears as a single
 * point per deploy in the metrics backend rather than as a recurring sample.
 *
 * <p>This bean is a no-op when neither {@link BuildProperties} nor {@link GitProperties} is
 * available — Spring Boot auto-publishes them when {@code spring-boot-maven-plugin}'s {@code
 * build-info} goal runs and when {@code git.properties} is on the classpath, respectively.
 */
public final class DeployInfoMetrics implements SmartInitializingSingleton {

    public static final String GAUGE_NAME = "pulse.deploy.info";

    private final MeterRegistry registry;
    private final @Nullable BuildProperties buildProperties;
    private final @Nullable GitProperties gitProperties;

    public DeployInfoMetrics(
            MeterRegistry registry, @Nullable BuildProperties buildProperties, @Nullable GitProperties gitProperties) {
        this.registry = registry;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (buildProperties == null && gitProperties == null) return;

        List<Tag> tags = new ArrayList<>();
        if (buildProperties != null) {
            addIfPresent(tags, "version", buildProperties.getVersion());
            addIfPresent(tags, "name", buildProperties.getName());
            if (buildProperties.getTime() != null) {
                addIfPresent(tags, "build.time", buildProperties.getTime().toString());
            }
        }
        if (gitProperties != null) {
            addIfPresent(tags, "commit", firstNonBlank(gitProperties.getShortCommitId(), gitProperties.getCommitId()));
            addIfPresent(tags, "branch", gitProperties.getBranch());
        }

        double startupEpochSeconds = System.currentTimeMillis() / 1000.0;
        Gauge.builder(GAUGE_NAME, () -> startupEpochSeconds)
                .description("Deploy marker gauge — value is process start epoch seconds, tags carry version/commit")
                .baseUnit("seconds")
                .tags(Tags.of(tags))
                .strongReference(true)
                .register(registry);
    }

    private static void addIfPresent(List<Tag> tags, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            tags.add(Tag.of(key, value));
        }
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
