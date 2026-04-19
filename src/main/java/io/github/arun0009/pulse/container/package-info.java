/**
 * Container-level resource observability.
 *
 * <p>Reads cgroup v1 and v2 pseudo-files to fill the gap between Micrometer's JVM-only memory
 * metrics ({@code jvm.memory.*}) and the kernel's view that determines whether your pod gets
 * OOM-killed. Off-heap, direct buffers, JIT, metaspace, and native libraries all show up in
 * the cgroup numbers but not in {@code jvm.memory.used}.
 */
@org.jspecify.annotations.NullMarked
package io.github.arun0009.pulse.container;
