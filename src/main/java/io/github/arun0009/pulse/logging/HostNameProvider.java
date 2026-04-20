package io.github.arun0009.pulse.logging;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the local host name used to populate the {@code host.name} OTel resource attribute
 * stamped on every Pulse log line.
 *
 * <h2>Why this is an SPI</h2>
 *
 * <p>{@link java.net.InetAddress#getLocalHost() InetAddress.getLocalHost()} is the default
 * source, but it has well-known sharp edges: it can block on misconfigured DNS, return
 * {@code "localhost"} on minimal Alpine images, and throw {@link java.net.UnknownHostException}
 * on hosts that don't have a hostname → IP mapping. Teams that already have a more reliable
 * source (the EC2 metadata service, the Kubernetes downward API written to a file, an internal
 * service-discovery client) can publish their own implementation and Pulse will use it
 * everywhere it currently calls {@code InetAddress.getLocalHost()}.
 *
 * <h2>How Pulse picks one up</h2>
 *
 * <p>A host name is needed before any Spring bean exists (the
 * {@link PulseLoggingEnvironmentPostProcessor} seeds {@code pulse.host.name} as a JVM system
 * property at {@code EnvironmentPostProcessor} time so the JSON layout can substitute it).
 * Because of that, Pulse discovers user-supplied implementations through Spring's
 * {@code spring.factories} mechanism, not the application context. To register your own:
 *
 * <pre>
 * # src/main/resources/META-INF/spring.factories
 * io.github.arun0009.pulse.logging.HostNameProvider=\
 *     com.acme.observability.Ec2MetadataHostNameProvider
 * </pre>
 *
 * <p>Pulse also registers a default implementation as the Spring bean
 * {@code pulseHostNameProvider} (type {@link HostNameProvider}) so any runtime code that wants
 * the same value (custom health indicators, scheduled jobs that emit hostname-tagged metrics)
 * can inject it directly. Override it with your own {@code @Bean} of type
 * {@link HostNameProvider} — {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
 * suppresses Pulse's default when yours is present. For log layout seeding at
 * {@code EnvironmentPostProcessor} time (before beans exist), still register the same class in
 * {@code META-INF/spring.factories} as documented above so EPP and the runtime bean stay aligned.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Must be cheap — called once at startup, but a future-Pulse runtime may also call it
 *       per-request, so do not perform blocking I/O in the hot path.</li>
 *   <li>Must never throw. Return {@code null} (or a blank string) to let Pulse fall back to
 *       its default detection chain (env vars → {@code InetAddress.getLocalHost()}).</li>
 *   <li>Implementations should be thread-safe.</li>
 * </ul>
 *
 * @since 2.0.0 (was a package-private test seam in 1.x)
 */
@FunctionalInterface
public interface HostNameProvider {

    /**
     * Returns the local host name, or {@code null} (or a blank string) to delegate to Pulse's
     * default chain.
     */
    @Nullable String localHostName();
}
