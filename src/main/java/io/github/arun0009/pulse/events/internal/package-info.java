/**
 * Internal wiring for {@link io.github.arun0009.pulse.events.SpanEvents} — the three built-in
 * {@link io.micrometer.observation.ObservationHandler ObservationHandler} implementations
 * (counter, span event, structured log) and the auto-configuration that exposes them as beans.
 *
 * <p>Anything in this package is implementation detail and may change between minor versions.
 * Application code should depend on {@link io.github.arun0009.pulse.events.SpanEvents} and
 * {@link io.github.arun0009.pulse.events.PulseEventContext} from the public package.
 */
@NullMarked
package io.github.arun0009.pulse.events.internal;

import org.jspecify.annotations.NullMarked;
