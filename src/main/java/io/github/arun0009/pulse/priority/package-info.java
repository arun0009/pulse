/**
 * Request-criticality propagation. The {@link io.github.arun0009.pulse.priority.RequestPriority}
 * vocabulary travels end-to-end on the {@code Pulse-Priority} header and OTel baggage so every
 * service in a call chain can make consistent load-shedding decisions on the same signal.
 */
@NullMarked
package io.github.arun0009.pulse.priority;

import org.jspecify.annotations.NullMarked;
