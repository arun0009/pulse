package io.github.arun0009.pulse.profiling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PyroscopeDetector}. The detector's job is to be honest about whether
 * the Pyroscope agent is present without ever throwing — these tests pin that contract by
 * setting and unsetting Pyroscope's standard system properties around each scenario.
 */
class PyroscopeDetectorTest {

    @AfterEach
    void clearProperties() {
        // Don't pollute other tests — system properties leak across the JVM.
        System.clearProperty("pyroscope.application.name");
        System.clearProperty("pyroscope.server.address");
    }

    @Test
    void detector_returns_absent_when_no_pyroscope_signals_are_present() {
        // Baseline: no system properties set, no agent class on classpath in test JVM.
        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();
        assertThat(detection.present()).isFalse();
        assertThat(detection.applicationName()).isNull();
        assertThat(detection.serverAddress()).isNull();
    }

    @Test
    void detector_returns_present_when_application_name_property_is_set() {
        System.setProperty("pyroscope.application.name", "orders-svc");

        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();

        assertThat(detection.present()).isTrue();
        assertThat(detection.applicationName()).isEqualTo("orders-svc");
        assertThat(detection.serverAddress()).isNull();
    }

    @Test
    void detector_returns_present_when_only_server_address_is_set() {
        // The Pyroscope agent allows omitting application.name; server.address alone is enough
        // to know the agent is configured. The detector must not require both.
        System.setProperty("pyroscope.server.address", "http://pyroscope:4040");

        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();

        assertThat(detection.present()).isTrue();
        assertThat(detection.serverAddress()).isEqualTo("http://pyroscope:4040");
    }

    @Test
    void detector_trims_whitespace_so_an_accidentally_padded_property_still_works() {
        System.setProperty("pyroscope.application.name", "  myapp  ");

        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();

        assertThat(detection.applicationName()).isEqualTo("myapp");
    }

    @Test
    void detector_treats_blank_value_as_absent_to_avoid_false_positives() {
        System.setProperty("pyroscope.application.name", "   ");

        PyroscopeDetector.Detection detection = PyroscopeDetector.detect();

        assertThat(detection.present())
                .as("A blank value typically comes from a misconfigured env-var substitution"
                        + " (PYROSCOPE_APP_NAME=). Detector must not promote that to 'present'.")
                .isFalse();
    }
}
