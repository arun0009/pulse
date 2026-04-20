package io.github.arun0009.pulse.profiling;

/**
 * Marker bean kept on the context so Spring does not garbage-collect the Pulse profiling
 * {@code SpanProcessor} registration. Produced by {@code PulseProfilingConfiguration} at
 * startup.
 *
 * <p>Carries the {@link PyroscopeDetector.Detection} snapshot that was observed at
 * registration time so {@code /actuator/pulse} can surface whether the Pyroscope agent was
 * actually loaded alongside Pulse — the most common "why isn't profile-trace correlation
 * working?" question has a one-click answer.
 */
public record ProfilingRegistration(PyroscopeDetector.Detection detection) {}
