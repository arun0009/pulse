/**
 * Fleet-wide consistency observability.
 *
 * <p>Each Pulse-instrumented JVM emits a stable hash of its resolved {@code pulse.*} configuration
 * as the {@code pulse.config.hash} gauge tag. Across a fleet of replicas, a Prometheus query
 * that counts distinct hashes per service surfaces stale ConfigMaps, partial deploys, and
 * env-var typos before they become outages.
 */
@NullMarked
package io.github.arun0009.pulse.fleet;

import org.jspecify.annotations.NullMarked;
