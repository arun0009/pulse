/**
 * OpenFeature observability integration. When the {@code dev.openfeature:sdk} is on the
 * classpath, Pulse threads every flag evaluation onto MDC and stamps an OTel-semconv-aligned
 * {@code feature_flag} span event so logs and traces explain why each request branched the way
 * it did.
 */
@NullMarked
package io.github.arun0009.pulse.openfeature;

import org.jspecify.annotations.NullMarked;
