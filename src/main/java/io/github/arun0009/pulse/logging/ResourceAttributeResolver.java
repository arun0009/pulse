package io.github.arun0009.pulse.logging;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves OpenTelemetry resource attributes that identify <em>where</em> the JVM is running:
 * the host, the container, the Kubernetes pod/node/namespace, and the cloud provider/region/AZ.
 *
 * <h2>Extending</h2>
 *
 * <p>Pulse 2.0 opens this class for subclassing so teams that need extra resource attributes
 * (custom deployment ids, tenant tags, internal cell identifiers) can extend it without
 * forking the detection chain. Two extension points are available:
 *
 * <ul>
 *   <li>Override {@link #contributeAdditional()} to <em>add</em> attributes — they are merged
 *       into {@link #resolveAll()} alongside the built-ins (built-ins win on key collision so
 *       the OTel semconv keys stay canonical).</li>
 *   <li>Override any of the protected accessors ({@link #hostName()}, {@link #containerId()},
 *       …, {@link #detectCloud()}) to <em>replace</em> a single detection — useful when your
 *       platform exposes the same value through a more reliable source (a downward-API file
 *       instead of an env var, the EC2 metadata service instead of {@code AWS_REGION}).</li>
 * </ul>
 *
 * <p>To activate a custom resolver at startup, register it with Spring's {@code spring.factories}
 * so the {@link PulseLoggingEnvironmentPostProcessor} picks it up before any bean exists:
 *
 * <pre>
 * # src/main/resources/META-INF/spring.factories
 * io.github.arun0009.pulse.logging.ResourceAttributeResolver=\
 *     com.acme.observability.AcmeResourceAttributeResolver
 * </pre>
 *
 * <p>After the context starts, Pulse also exposes a {@code ResourceAttributeResolver} bean named
 * {@code pulseResourceAttributeResolver} (wrapping the default chain or your
 * {@link HostNameProvider} bean) so application code and {@link io.github.arun0009.pulse.actuator.PulseDiagnostics}
 * can call {@link #resolveAll()} at runtime. If you use a {@code spring.factories}-only custom
 * subclass, publish the <em>same</em> instance (or an equivalent subclass) as a {@code @Bean} so
 * runtime injection and the EPP stay consistent; {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
 * suppresses Pulse's stock bean when yours is present.
 *
 * <p>These attributes are <em>per-process</em> (not per-request), so the resolver runs once at
 * startup. The resolved values are written as JVM system properties by
 * {@link PulseLoggingEnvironmentPostProcessor}, where they are then stamped on every log line
 * by both the Log4j2 JSON layout and the Logback encoder. This is the same mechanism Pulse
 * already uses for {@code service.version} and {@code build.commit}.
 *
 * <h2>Relationship to {@code opentelemetry-resources}</h2>
 *
 * <p>The OTel SDK's optional {@code opentelemetry-resources} artifact ships ServiceLoader-based
 * detectors ({@code ContainerResource}, {@code HostResource}, {@code OsResource},
 * {@code ProcessResource}) that enrich the SDK {@code Resource} attached to <em>spans and
 * metrics</em>. Pulse delegates to those for the trace/metric path — they are the canonical
 * source. What this resolver covers is the <em>log</em> path: the JSON log layout cannot read
 * the SDK {@code Resource}, so Pulse derives the same values once at startup and exposes them
 * as system properties the layout substitutes into every log line. The two paths are
 * complementary — the SDK enriches OTLP signals, this resolver enriches log records — and the
 * resolver always honors operator overrides via the OTel-standard
 * {@code OTEL_RESOURCE_ATTRIBUTES} env var, so a single override flows everywhere.
 *
 * <h2>Resolution sources (highest priority first)</h2>
 *
 * <ol>
 *   <li><b>{@code OTEL_RESOURCE_ATTRIBUTES} env var</b> — operator override. If you set
 *       {@code cloud.region=us-east-1} there, that wins over auto-detection. This matches the
 *       OTel SDK's behavior so Pulse stays consistent with the rest of the OTel ecosystem.</li>
 *   <li><b>Platform-specific env vars</b> — {@code AWS_REGION}, {@code POD_NAME},
 *       {@code KUBERNETES_SERVICE_HOST}, etc. Set by the platform (EKS / ECS / GKE / Azure /
 *       Fargate / Cloud Run / your own Helm chart's downward API).</li>
 *   <li><b>OS-level introspection</b> — {@code /proc/self/cgroup} for the container ID;
 *       {@link InetAddress#getLocalHost()} for the host name.</li>
 * </ol>
 *
 * <p>Every accessor returns {@code null} when no source produced a value; callers seed the
 * system property only when a value is present so the layout substitution falls back to the
 * configured default ({@code "unknown"}) on undetected fields rather than emitting empty
 * strings.
 *
 * <h2>Performance &amp; safety</h2>
 *
 * <p>Detection runs exactly once at startup. The cgroup file read is wrapped in a defensive
 * try-catch — Pulse must never block JVM startup because some weird container runtime denied
 * read access to {@code /proc}. {@link SecurityException} from {@link System#getenv(String)} is
 * also tolerated for hardened JVM deployments.
 */
public class ResourceAttributeResolver {

    /** OTel resource attribute keys checked in {@code OTEL_RESOURCE_ATTRIBUTES}. */
    static final String OTEL_HOST_NAME = "host.name";

    static final String OTEL_CONTAINER_ID = "container.id";
    static final String OTEL_K8S_POD_NAME = "k8s.pod.name";
    static final String OTEL_K8S_NAMESPACE = "k8s.namespace.name";
    static final String OTEL_K8S_NODE_NAME = "k8s.node.name";
    static final String OTEL_CLOUD_PROVIDER = "cloud.provider";
    static final String OTEL_CLOUD_REGION = "cloud.region";
    static final String OTEL_CLOUD_AZ = "cloud.availability_zone";

    /** Platform env-var lookup chains. First non-blank wins. */
    static final List<String> POD_NAME_ENV_VARS = List.of("POD_NAME", "MY_POD_NAME", "K8S_POD_NAME");

    static final List<String> POD_NAMESPACE_ENV_VARS =
            List.of("POD_NAMESPACE", "MY_POD_NAMESPACE", "K8S_NAMESPACE", "NAMESPACE");
    static final List<String> NODE_NAME_ENV_VARS = List.of("NODE_NAME", "MY_NODE_NAME", "K8S_NODE_NAME");
    static final List<String> AWS_REGION_ENV_VARS = List.of("AWS_REGION", "AWS_DEFAULT_REGION");
    static final List<String> GCP_REGION_ENV_VARS =
            List.of("GOOGLE_CLOUD_REGION", "GCP_REGION", "FUNCTION_REGION", "CLOUD_RUN_REGION");
    static final List<String> AZURE_REGION_ENV_VARS = List.of("AZURE_REGION", "REGION");
    static final List<String> AWS_AZ_ENV_VARS = List.of("AWS_AVAILABILITY_ZONE");
    static final List<String> KUBERNETES_SIGNAL_ENV_VARS = List.of("KUBERNETES_SERVICE_HOST");

    /** OTel cgroup container-ID extractor — 64 hex characters, the canonical container ID length. */
    private static final Pattern CONTAINER_ID_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /** Path of the cgroup file inside any modern Linux container. */
    static final String CGROUP_PATH = "/proc/self/cgroup";

    /** Path Kubernetes mounts the namespace into for pods using a service account. */
    static final String K8S_NAMESPACE_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

    private final Function<String, @Nullable String> envLookup;
    private final Function<Path, @Nullable String> fileReader;
    private final HostNameProvider hostNameProvider;

    public ResourceAttributeResolver() {
        this(ResourceAttributeResolver::safeGetLocalHostName);
    }

    /**
     * Public constructor accepting a custom {@link HostNameProvider} — the most common
     * extension reason. The other lookups (env vars, system properties, cgroup file) are still
     * the platform defaults; subclasses needing to swap those should use the protected
     * test-seam constructor.
     */
    public ResourceAttributeResolver(HostNameProvider hostNameProvider) {
        this(ResourceAttributeResolver::safeGetEnv, ResourceAttributeResolver::safeReadFile, hostNameProvider);
    }

    /**
     * Test/extension constructor — lets subclasses swap every external interaction. Was
     * package-private in 1.x and is now {@code protected} so user-supplied subclasses can
     * stub I/O the same way Pulse's own tests do.
     */
    protected ResourceAttributeResolver(
            Function<String, @Nullable String> envLookup,
            Function<Path, @Nullable String> fileReader,
            HostNameProvider hostNameProvider) {
        this.envLookup = envLookup;
        this.fileReader = fileReader;
        this.hostNameProvider = hostNameProvider;
    }

    /**
     * Returns a stable {@link Map} of resolved attribute name → value, using the OTel semantic
     * convention names as keys ({@code host.name}, {@code container.id}, etc.). Only attributes
     * for which a source produced a non-blank value are present in the map, so callers can iterate
     * safely without checking for nulls.
     *
     * <p>The map preserves insertion order to make logs deterministic across runs of the same
     * process, simplifying snapshot tests downstream.
     */
    public final Map<String, String> resolveAll() {
        Map<String, String> out = new LinkedHashMap<>(8);

        // Subclass extras first so user keys are visible to debugging, but built-in OTel
        // semconv keys take precedence on collision so dashboards keep their canonical shape.
        for (Map.Entry<String, String> extra : contributeAdditional().entrySet()) {
            putIfPresent(out, extra.getKey(), extra.getValue());
        }

        putIfPresent(out, OTEL_HOST_NAME, hostName());
        putIfPresent(out, OTEL_CONTAINER_ID, containerId());
        putIfPresent(out, OTEL_K8S_POD_NAME, kubernetesPodName());
        putIfPresent(out, OTEL_K8S_NAMESPACE, kubernetesNamespace());
        putIfPresent(out, OTEL_K8S_NODE_NAME, kubernetesNodeName());
        CloudPlatform cloud = detectCloud();
        putIfPresent(out, OTEL_CLOUD_PROVIDER, cloud.provider);
        putIfPresent(out, OTEL_CLOUD_REGION, cloud.region);
        putIfPresent(out, OTEL_CLOUD_AZ, cloud.availabilityZone);
        return out;
    }

    /**
     * Extension hook for adding custom attributes that {@link #resolveAll()} should expose
     * alongside the built-in OTel semconv detections. Built-in keys win on collision so the
     * canonical OTel shape is preserved; user keys should follow the OTel naming conventions
     * (dot-segmented, lower-snake) so downstream dashboards can group them sensibly.
     *
     * <p>Default implementation returns an empty map. Subclasses override to contribute, for
     * example, {@code deployment.id}, {@code instance.type}, or {@code cell.name}.
     *
     * <p>Implementations must be cheap (called once at startup) and must never throw.
     */
    protected Map<String, String> contributeAdditional() {
        return Map.of();
    }

    /** Resolved {@code host.name} attribute, or {@code null} if every source returned blank. */
    protected @Nullable String hostName() {
        String fromOtel = fromOtelResourceAttribute(OTEL_HOST_NAME);
        if (fromOtel != null) return fromOtel;

        String fromEnv = firstNonBlank(List.of("HOSTNAME", "COMPUTERNAME"));
        if (fromEnv != null) return fromEnv;

        String fromInet = hostNameProvider.localHostName();
        return blankToNull(fromInet);
    }

    /** Resolved {@code container.id} attribute, or {@code null} if no source produced one. */
    protected @Nullable String containerId() {
        String fromOtel = fromOtelResourceAttribute(OTEL_CONTAINER_ID);
        if (fromOtel != null) return fromOtel;

        String cgroup = fileReader.apply(Path.of(CGROUP_PATH));
        if (cgroup == null) return null;

        Matcher matcher = CONTAINER_ID_PATTERN.matcher(cgroup);
        return matcher.find() ? matcher.group() : null;
    }

    /** Resolved {@code k8s.pod.name} attribute, or {@code null} when not running on K8s. */
    protected @Nullable String kubernetesPodName() {
        String fromOtel = fromOtelResourceAttribute(OTEL_K8S_POD_NAME);
        if (fromOtel != null) return fromOtel;

        String fromEnv = firstNonBlank(POD_NAME_ENV_VARS);
        if (fromEnv != null) return fromEnv;

        // Fallback: HOSTNAME on K8s is set to the pod name when the pod doesn't override it.
        // We only trust this when KUBERNETES_SERVICE_HOST proves we're actually on K8s, otherwise
        // a developer's laptop hostname would masquerade as a pod name in logs.
        if (firstNonBlank(KUBERNETES_SIGNAL_ENV_VARS) != null) {
            return blankToNull(envLookup.apply("HOSTNAME"));
        }
        return null;
    }

    /** Resolved {@code k8s.namespace.name} attribute, or {@code null} when not running on K8s. */
    protected @Nullable String kubernetesNamespace() {
        String fromOtel = fromOtelResourceAttribute(OTEL_K8S_NAMESPACE);
        if (fromOtel != null) return fromOtel;

        String fromEnv = firstNonBlank(POD_NAMESPACE_ENV_VARS);
        if (fromEnv != null) return fromEnv;

        // Kubernetes mounts the namespace into every pod that uses a service account,
        // even when the operator forgot to project it via the downward API.
        return fileReader.apply(Path.of(K8S_NAMESPACE_FILE));
    }

    /** Resolved {@code k8s.node.name} attribute, or {@code null} when not running on K8s. */
    protected @Nullable String kubernetesNodeName() {
        String fromOtel = fromOtelResourceAttribute(OTEL_K8S_NODE_NAME);
        if (fromOtel != null) return fromOtel;
        return firstNonBlank(NODE_NAME_ENV_VARS);
    }

    /**
     * Detects cloud provider, region, and availability zone in a single pass so the values stay
     * consistent — we never want to label a log line as {@code cloud.provider=aws,
     * cloud.region=us-central1} because two different probes ran independently.
     */
    protected CloudPlatform detectCloud() {
        String otelProvider = fromOtelResourceAttribute(OTEL_CLOUD_PROVIDER);
        String otelRegion = fromOtelResourceAttribute(OTEL_CLOUD_REGION);
        String otelAz = fromOtelResourceAttribute(OTEL_CLOUD_AZ);
        if (otelProvider != null || otelRegion != null || otelAz != null) {
            return new CloudPlatform(otelProvider, otelRegion, otelAz);
        }

        String awsRegion = firstNonBlank(AWS_REGION_ENV_VARS);
        String awsAz = firstNonBlank(AWS_AZ_ENV_VARS);
        if (awsRegion != null || awsAz != null || envLookup.apply("AWS_EXECUTION_ENV") != null) {
            return new CloudPlatform("aws", awsRegion, awsAz);
        }

        String gcpRegion = firstNonBlank(GCP_REGION_ENV_VARS);
        if (gcpRegion != null || envLookup.apply("GOOGLE_CLOUD_PROJECT") != null) {
            return new CloudPlatform("gcp", gcpRegion, null);
        }

        String azureRegion = firstNonBlank(AZURE_REGION_ENV_VARS);
        if (azureRegion != null || envLookup.apply("WEBSITE_SITE_NAME") != null) {
            return new CloudPlatform("azure", azureRegion, null);
        }

        return CloudPlatform.NONE;
    }

    @Nullable private String fromOtelResourceAttribute(String attributeKey) {
        String raw = envLookup.apply(PulseLoggingEnvironmentPostProcessor.OTEL_RESOURCE_ATTRIBUTES_ENV);
        return PulseLoggingEnvironmentPostProcessor.parseOtelAttribute(raw, attributeKey);
    }

    @Nullable private String firstNonBlank(List<String> envVarNames) {
        for (String name : envVarNames) {
            String value = blankToNull(envLookup.apply(name));
            if (value != null) return value;
        }
        return null;
    }

    private static void putIfPresent(Map<String, String> out, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value.trim());
        }
    }

    @Nullable private static String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    @Nullable private static String safeGetEnv(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException ignored) {
            return null;
        }
    }

    @Nullable private static String safeReadFile(Path path) {
        try {
            if (!Files.isReadable(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // /proc filesystem reads can fail in seccomp-restricted runtimes; never block startup.
            // RuntimeException covers SecurityException (subclass) and any oddball NIO exceptions.
            return null;
        }
    }

    @Nullable private static String safeGetLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | SecurityException ignored) {
            return null;
        }
    }

    /**
     * Captures detected cloud-platform attributes as a single immutable triple so callers can't
     * accidentally mix providers and regions across different detection paths.
     */
    public record CloudPlatform(
            @Nullable String provider,
            @Nullable String region,
            @Nullable String availabilityZone) {
        public static final CloudPlatform NONE = new CloudPlatform(null, null, null);
    }
}
