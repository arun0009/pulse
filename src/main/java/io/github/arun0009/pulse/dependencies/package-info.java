/**
 * Per-dependency RED metrics + per-request fan-out tracking — the caller-side answer to "which
 * downstream is hurting me?" without opening N dashboards.
 *
 * <p>Every outbound HTTP call is tagged with a logical {@code pulse.dependency} resolved from the
 * configured {@code pulse.dependencies.map} so {@code api.payments.internal} and
 * {@code grpc.payments.internal} both roll up to {@code payment-service}. Recordings are emitted
 * by {@link io.github.arun0009.pulse.dependencies.DependencyOutboundRecorder} so the tag and
 * meter shapes are identical across RestTemplate, RestClient, WebClient, and OkHttp.
 *
 * <p>{@link io.github.arun0009.pulse.dependencies.RequestFanoutFilter} sits in the servlet chain
 * and converts the per-request thread-local from
 * {@link io.github.arun0009.pulse.dependencies.RequestFanout} into Micrometer distributions plus
 * span attributes — the per-request side of the same signal.
 */
@NullMarked
package io.github.arun0009.pulse.dependencies;

import org.jspecify.annotations.NullMarked;
