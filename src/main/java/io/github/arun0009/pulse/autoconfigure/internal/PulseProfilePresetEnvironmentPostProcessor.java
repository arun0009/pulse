package io.github.arun0009.pulse.autoconfigure.internal;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auto-applies Pulse's shipped profile presets ({@code pulse-dev}, {@code pulse-prod},
 * {@code pulse-test}, {@code pulse-canary}) when the host application activates a matching
 * environment profile.
 *
 * <h2>Why this exists</h2>
 *
 * Pulse ships four opinionated YAML files at the standard Spring location
 * ({@code src/main/resources/application-pulse-{env}.yml}). Loading one is purely standard
 * Spring &mdash; just include the matching profile in {@code spring.profiles.active}. This EPP
 * removes the one-line piece of boilerplate (and the easy-to-forget step) by mapping common
 * environment names to the matching Pulse preset:
 *
 * <ul>
 *   <li>{@code dev} &rarr; appends {@code pulse-dev}</li>
 *   <li>{@code prod} (also {@code production}) &rarr; appends {@code pulse-prod}</li>
 *   <li>{@code test} &rarr; appends {@code pulse-test}</li>
 *   <li>{@code canary} &rarr; appends {@code pulse-canary}</li>
 * </ul>
 *
 * If the matching preset is already in the active profiles (the user opted in explicitly), or
 * if {@code pulse.profile-presets.auto-apply=false}, this processor does nothing.
 *
 * <h2>How it integrates with Spring's config-data machinery</h2>
 *
 * Spring Boot's {@code ConfigDataEnvironmentPostProcessor} runs at order
 * {@link Ordered#HIGHEST_PRECEDENCE HIGHEST_PRECEDENCE + 10}. We run earlier so the profile we
 * append is visible by the time the config-data processor scans for {@code application-{profile}.yml}
 * resources.
 *
 * <h2>Customising the mapping</h2>
 *
 * The {@code env -> pulse-profile} mapping is also driven by {@code pulse.profile-presets.presets}
 * and is read from the environment so projects can teach Pulse about their own profile names
 * (e.g. {@code stage -> pulse-prod}).
 */
public final class PulseProfilePresetEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * Built-in environment-name &rarr; Pulse-preset-profile mappings. Lowercase keys; matching is
     * case-insensitive against the host application's active profiles.
     */
    static final Map<String, String> DEFAULT_PRESETS;

    static {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("dev", "pulse-dev");
        defaults.put("development", "pulse-dev");
        defaults.put("local", "pulse-dev");
        defaults.put("prod", "pulse-prod");
        defaults.put("production", "pulse-prod");
        defaults.put("test", "pulse-test");
        defaults.put("integration", "pulse-test");
        defaults.put("canary", "pulse-canary");
        defaults.put("shadow", "pulse-canary");
        DEFAULT_PRESETS = Map.copyOf(defaults);
    }

    static final String AUTO_APPLY_PROPERTY = "pulse.profile-presets.auto-apply";
    static final String PRESETS_PROPERTY_PREFIX = "pulse.profile-presets.presets.";

    @Override
    public int getOrder() {
        // Run before ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10) so that the
        // pulse-* profile we append is visible when Spring scans for application-{profile}.yml.
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(AUTO_APPLY_PROPERTY, Boolean.class, true)) {
            return;
        }

        Map<String, String> presets = mergePresets(environment);
        Set<String> active = new LinkedHashSet<>(Arrays.asList(environment.getActiveProfiles()));
        Set<String> activeLowercase = new LinkedHashSet<>();
        for (String profile : active) {
            activeLowercase.add(profile.toLowerCase(Locale.ROOT));
        }

        for (String envProfile : List.copyOf(active)) {
            String pulseProfile = presets.get(envProfile.toLowerCase(Locale.ROOT));
            if (pulseProfile == null) continue;
            if (activeLowercase.contains(pulseProfile.toLowerCase(Locale.ROOT))) continue;

            // addActiveProfile is no-op if the profile is already there, but we lowercase-checked
            // above so we know we are adding net-new.
            environment.addActiveProfile(pulseProfile);
            activeLowercase.add(pulseProfile.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Returns the effective {@code env -> pulse-profile} map: built-in defaults overlaid with any
     * user-supplied {@code pulse.profile-presets.presets.<env>=<pulse-profile>} overrides from
     * the environment. Keys are lowercased so matching is case-insensitive.
     */
    private static Map<String, String> mergePresets(ConfigurableEnvironment environment) {
        Map<String, String> merged = new LinkedHashMap<>();
        DEFAULT_PRESETS.forEach((k, v) -> merged.put(k.toLowerCase(Locale.ROOT), v));
        // Property names use the relaxed-binding-friendly prefix; we look up each known env name.
        // We intentionally do not use Binder here because it is heavyweight for an EPP and pulls
        // in metadata that may not be on the classpath at this stage.
        for (String envName : List.copyOf(merged.keySet())) {
            String override = environment.getProperty(PRESETS_PROPERTY_PREFIX + envName);
            if (override != null && !override.isBlank()) {
                merged.put(envName, override.trim());
            }
        }
        return merged;
    }
}
