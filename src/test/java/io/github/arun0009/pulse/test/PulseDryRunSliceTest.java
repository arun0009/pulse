package io.github.arun0009.pulse.test;

import io.github.arun0009.pulse.enforcement.PulseEnforcementMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link PulseDryRun} contract so future refactors can't quietly turn the slice into a
 * no-op:
 *
 * <ul>
 *   <li>The annotation must compose {@link SpringBootTest @SpringBootTest} so a class that
 *       carries only {@code @PulseDryRun} actually boots a Spring application context.</li>
 *   <li>It must {@link Import} {@link PulseTestConfiguration} so {@link PulseTestHarness} and
 *       the in-memory OTel SDK are wired into that context.</li>
 *   <li>It must publish {@code pulse.enforcement.mode=DRY_RUN} via {@link TestPropertySource}
 *       so Pulse boots in observe-only mode.</li>
 * </ul>
 *
 * <p>The full end-to-end wiring (auto-configuration honouring the property and producing a
 * {@link PulseEnforcementMode} bean seeded to {@code DRY_RUN}) is exercised by the
 * autoconfiguration tests; this test only guarantees the slice annotation contributes the
 * three pieces above.
 */
class PulseDryRunSliceTest {

    @Test
    void dry_run_annotation_composes_spring_boot_test() {
        SpringBootTest spring = AnnotationUtils.findAnnotation(PulseDryRun.class, SpringBootTest.class);
        assertThat(spring)
                .as("@PulseDryRun must compose @SpringBootTest so users don't need to add it themselves")
                .isNotNull();
    }

    @Test
    void dry_run_annotation_imports_pulse_test_configuration() {
        Import imp = AnnotationUtils.findAnnotation(PulseDryRun.class, Import.class);
        assertThat(imp).as("@PulseDryRun must @Import PulseTestConfiguration").isNotNull();
        assertThat(imp.value())
                .as("the imported configuration must wire the test harness")
                .contains(PulseTestConfiguration.class);
    }

    @Test
    void dry_run_annotation_flips_enforcement_property() {
        TestPropertySource source = AnnotationUtils.findAnnotation(PulseDryRun.class, TestPropertySource.class);
        assertThat(source)
                .as("@PulseDryRun must carry a @TestPropertySource so JUnit/Spring picks up the override")
                .isNotNull();
        assertThat(Arrays.asList(source.properties()))
                .as("the property must flip the enforcement mode to DRY_RUN")
                .contains("pulse.enforcement.mode=DRY_RUN");
    }

    @Test
    void dry_run_property_round_trips_into_enforcement_mode_enum() {
        PulseEnforcementMode mode = new PulseEnforcementMode(PulseEnforcementMode.Mode.valueOf("DRY_RUN"));
        assertThat(mode.dryRun()).isTrue();
        assertThat(mode.enforcing()).isFalse();
    }
}
