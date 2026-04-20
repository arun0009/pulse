package io.github.arun0009.pulse.enforcement.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridge from the legacy 1.1-snapshot property {@code pulse.runtime.mode} to the canonical
 * {@code pulse.enforcement.mode}.
 *
 * <p>{@link io.github.arun0009.pulse.enforcement.EnforcementProperties} replaced an
 * earlier {@code Runtime} record. To avoid breaking users who pinned to a {@code 1.1} snapshot,
 * this {@link EnvironmentPostProcessor} reads {@code pulse.runtime.mode} from any property
 * source and republishes it as {@code pulse.enforcement.mode} (only when the canonical key is
 * not already set), with a one-line deprecation WARN.
 *
 * <p>Plan: remove this class in 1.3. The metadata in
 * {@code META-INF/additional-spring-configuration-metadata.json} marks {@code pulse.runtime.mode}
 * as deprecated so IDEs surface the rename in the meantime.
 */
public final class PulseEnforcementModeMigrationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(PulseEnforcementModeMigrationEnvironmentPostProcessor.class);

    static final String LEGACY_PROPERTY = "pulse.runtime.mode";
    static final String CANONICAL_PROPERTY = "pulse.enforcement.mode";

    @Override
    public int getOrder() {
        // Run before PulseProfilePresetEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 5) so that
        // the rename is in place before any preset that reads the canonical property fires.
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String legacy = environment.getProperty(LEGACY_PROPERTY);
        if (legacy == null || legacy.isBlank()) {
            return;
        }
        if (environment.getProperty(CANONICAL_PROPERTY) != null) {
            log.warn(
                    "Pulse: both {} (deprecated) and {} are set. The canonical property wins;"
                            + " remove {} from your configuration.",
                    LEGACY_PROPERTY,
                    CANONICAL_PROPERTY,
                    LEGACY_PROPERTY);
            return;
        }
        log.warn(
                "Pulse: '{}' is deprecated and will be removed in 1.3. Migrate to '{}'.",
                LEGACY_PROPERTY,
                CANONICAL_PROPERTY);
        Map<String, Object> bridge = new HashMap<>();
        bridge.put(CANONICAL_PROPERTY, legacy);
        environment.getPropertySources().addFirst(new MapPropertySource("pulseEnforcementModeLegacyBridge", bridge));
    }
}
