package io.github.arun0009.pulse.logging;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior of the resource-attribute resolver that detects host / container / Kubernetes / cloud
 * identifiers used to stamp every log line with <em>where</em> the JVM is running.
 *
 * <p>The resolver is wired through pluggable lookup functions so each test exercises one source
 * in isolation — a regression in one detection path doesn't get masked by another fallback.
 *
 * <p>Reads of {@code /proc/self/cgroup}, environment variables, and {@code InetAddress.getLocalHost()}
 * are all stubbed; we never want a unit test to depend on the host it happens to run on.
 */
class ResourceAttributeResolverTest {

    @Nested
    class Host_name {

        @Test
        void prefers_otel_resource_attribute_over_env_and_inet_address() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "OTEL_RESOURCE_ATTRIBUTES", "host.name=otel-host",
                            "HOSTNAME", "env-host"),
                    sysPropOf(),
                    fileReaderOf(),
                    () -> "inet-host");

            assertThat(resolver.hostName()).isEqualTo("otel-host");
        }

        @Test
        void falls_back_to_HOSTNAME_env_when_otel_attribute_missing() {
            ResourceAttributeResolver resolver =
                    resolverWith(envOf("HOSTNAME", "pod-7"), sysPropOf(), fileReaderOf(), () -> "inet-host");

            assertThat(resolver.hostName()).isEqualTo("pod-7");
        }

        @Test
        void falls_back_to_inet_address_when_no_env_set() {
            ResourceAttributeResolver resolver =
                    resolverWith(envOf(), sysPropOf(), fileReaderOf(), () -> "laptop.local");

            assertThat(resolver.hostName()).isEqualTo("laptop.local");
        }

        @Test
        void returns_null_when_inet_address_lookup_fails() {
            ResourceAttributeResolver resolver = resolverWith(envOf(), sysPropOf(), fileReaderOf(), () -> null);

            assertThat(resolver.hostName()).isNull();
        }
    }

    @Nested
    class Container_id {

        @Test
        void extracts_64_hex_chars_from_cgroup_v1_docker_line() {
            String cgroup = "12:cpu,cpuacct:/docker/abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\n";
            ResourceAttributeResolver resolver =
                    resolverWith(envOf(), sysPropOf(), fileReaderOf(ResourceAttributeResolver.CGROUP_PATH, cgroup));

            assertThat(resolver.containerId())
                    .isEqualTo("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        }

        @Test
        void extracts_64_hex_chars_from_cgroup_v2_systemd_format() {
            String cgroup =
                    "0::/system.slice/docker-1111111122222222333333334444444455555555666666667777777788888888.scope\n";
            ResourceAttributeResolver resolver =
                    resolverWith(envOf(), sysPropOf(), fileReaderOf(ResourceAttributeResolver.CGROUP_PATH, cgroup));

            assertThat(resolver.containerId())
                    .isEqualTo("1111111122222222333333334444444455555555666666667777777788888888");
        }

        @Test
        void prefers_otel_resource_attribute_over_cgroup() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf("OTEL_RESOURCE_ATTRIBUTES", "container.id=manually-overridden"),
                    sysPropOf(),
                    fileReaderOf(
                            ResourceAttributeResolver.CGROUP_PATH,
                            "12:cpu:/docker/0000000000000000000000000000000000000000000000000000000000000000"));

            assertThat(resolver.containerId()).isEqualTo("manually-overridden");
        }

        @Test
        void returns_null_when_cgroup_unreadable_and_no_otel_attribute() {
            ResourceAttributeResolver resolver = resolverWith(envOf(), sysPropOf(), fileReaderOf());

            assertThat(resolver.containerId()).isNull();
        }

        @Test
        void returns_null_when_cgroup_has_no_64_hex_run() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(),
                    sysPropOf(),
                    fileReaderOf(ResourceAttributeResolver.CGROUP_PATH, "12:cpu:/user.slice/session-1.scope"));

            assertThat(resolver.containerId()).isNull();
        }
    }

    @Nested
    class Kubernetes_attributes {

        @Test
        void resolves_pod_namespace_node_from_downward_api_env_vars() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "POD_NAME", "orders-7d4b9c-x",
                            "POD_NAMESPACE", "checkout",
                            "NODE_NAME", "ip-10-0-1-23.ec2.internal"),
                    sysPropOf(),
                    fileReaderOf());

            assertThat(resolver.kubernetesPodName()).isEqualTo("orders-7d4b9c-x");
            assertThat(resolver.kubernetesNamespace()).isEqualTo("checkout");
            assertThat(resolver.kubernetesNodeName()).isEqualTo("ip-10-0-1-23.ec2.internal");
        }

        @Test
        void falls_back_to_HOSTNAME_for_pod_name_only_when_K8S_signal_is_present() {
            ResourceAttributeResolver inK8s = resolverWith(
                    envOf(
                            "KUBERNETES_SERVICE_HOST", "10.0.0.1",
                            "HOSTNAME", "orders-abc123"),
                    sysPropOf(),
                    fileReaderOf());
            assertThat(inK8s.kubernetesPodName()).isEqualTo("orders-abc123");

            ResourceAttributeResolver onLaptop =
                    resolverWith(envOf("HOSTNAME", "arun-mbp"), sysPropOf(), fileReaderOf());
            assertThat(onLaptop.kubernetesPodName())
                    .as("HOSTNAME alone is not enough — would falsely label a developer laptop as a pod")
                    .isNull();
        }

        @Test
        void reads_namespace_from_serviceaccount_mount_when_env_missing() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(), sysPropOf(), fileReaderOf(ResourceAttributeResolver.K8S_NAMESPACE_FILE, "production"));

            assertThat(resolver.kubernetesNamespace()).isEqualTo("production");
        }

        @Test
        void otel_resource_attribute_wins_over_env_and_file() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "OTEL_RESOURCE_ATTRIBUTES", "k8s.namespace.name=manual-override",
                            "POD_NAMESPACE", "env-says-this"),
                    sysPropOf(),
                    fileReaderOf());

            assertThat(resolver.kubernetesNamespace()).isEqualTo("manual-override");
        }
    }

    @Nested
    class Cloud_attributes {

        @Test
        void detects_aws_from_AWS_REGION() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "AWS_REGION", "us-east-1",
                            "AWS_AVAILABILITY_ZONE", "us-east-1a"),
                    sysPropOf(),
                    fileReaderOf());

            ResourceAttributeResolver.CloudPlatform cloud = resolver.detectCloud();
            assertThat(cloud.provider()).isEqualTo("aws");
            assertThat(cloud.region()).isEqualTo("us-east-1");
            assertThat(cloud.availabilityZone()).isEqualTo("us-east-1a");
        }

        @Test
        void detects_aws_lambda_via_AWS_EXECUTION_ENV_even_without_region() {
            ResourceAttributeResolver resolver =
                    resolverWith(envOf("AWS_EXECUTION_ENV", "AWS_Lambda_java21"), sysPropOf(), fileReaderOf());

            assertThat(resolver.detectCloud().provider()).isEqualTo("aws");
        }

        @Test
        void detects_gcp_from_GOOGLE_CLOUD_PROJECT() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "GOOGLE_CLOUD_PROJECT", "my-project",
                            "GOOGLE_CLOUD_REGION", "us-central1"),
                    sysPropOf(),
                    fileReaderOf());

            ResourceAttributeResolver.CloudPlatform cloud = resolver.detectCloud();
            assertThat(cloud.provider()).isEqualTo("gcp");
            assertThat(cloud.region()).isEqualTo("us-central1");
        }

        @Test
        void detects_azure_from_AZURE_REGION() {
            ResourceAttributeResolver resolver =
                    resolverWith(envOf("AZURE_REGION", "westeurope"), sysPropOf(), fileReaderOf());

            ResourceAttributeResolver.CloudPlatform cloud = resolver.detectCloud();
            assertThat(cloud.provider()).isEqualTo("azure");
            assertThat(cloud.region()).isEqualTo("westeurope");
        }

        @Test
        void otel_resource_attributes_win_over_platform_env_vars() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "OTEL_RESOURCE_ATTRIBUTES", "cloud.provider=fly,cloud.region=lhr",
                            "AWS_REGION", "us-east-1"),
                    sysPropOf(),
                    fileReaderOf());

            ResourceAttributeResolver.CloudPlatform cloud = resolver.detectCloud();
            assertThat(cloud.provider()).isEqualTo("fly");
            assertThat(cloud.region()).isEqualTo("lhr");
        }

        @Test
        void returns_NONE_when_no_cloud_signal_present() {
            ResourceAttributeResolver resolver = resolverWith(envOf(), sysPropOf(), fileReaderOf());

            assertThat(resolver.detectCloud()).isEqualTo(ResourceAttributeResolver.CloudPlatform.NONE);
        }
    }

    @Nested
    class Resolve_all {

        @Test
        void omits_keys_for_which_no_source_produced_a_value() {
            // No OTel attributes, no envs, empty cgroup, no hostname → empty map.
            ResourceAttributeResolver resolver = resolverWith(envOf(), sysPropOf(), fileReaderOf(), () -> null);

            assertThat(resolver.resolveAll()).isEmpty();
        }

        @Test
        void includes_only_detected_attributes_in_the_returned_map() {
            ResourceAttributeResolver resolver = resolverWith(
                    envOf(
                            "AWS_REGION", "eu-west-1",
                            "POD_NAME", "checkout-7"),
                    sysPropOf(),
                    fileReaderOf(),
                    () -> "host-x");

            Map<String, String> attributes = resolver.resolveAll();

            assertThat(attributes)
                    .containsEntry("host.name", "host-x")
                    .containsEntry("k8s.pod.name", "checkout-7")
                    .containsEntry("cloud.provider", "aws")
                    .containsEntry("cloud.region", "eu-west-1")
                    .doesNotContainKeys("container.id", "k8s.node.name", "cloud.availability_zone");
        }
    }

    /* ---------- helpers ---------- */

    private static ResourceAttributeResolver resolverWith(
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> sysProp,
            Function<Path, @Nullable String> file) {
        return resolverWith(env, sysProp, file, () -> null);
    }

    private static ResourceAttributeResolver resolverWith(
            Function<String, @Nullable String> env,
            Function<String, @Nullable String> sysProp,
            Function<Path, @Nullable String> file,
            ResourceAttributeResolver.HostNameProvider hostName) {
        return new ResourceAttributeResolver(env, sysProp, file, hostName);
    }

    /** Build a stub env from {@code (key, value, key, value, …)} pairs, returning null for misses. */
    private static Function<String, @Nullable String> envOf(String... keyValuePairs) {
        if ((keyValuePairs.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "envOf requires an even number of arguments (key, value, key, value, …)");
        }
        Map<String, String> backing = new HashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            backing.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return backing::get;
    }

    private static Function<String, @Nullable String> sysPropOf() {
        return name -> null;
    }

    private static Function<Path, @Nullable String> fileReaderOf() {
        return path -> null;
    }

    /** Stub a single file path → contents mapping; everything else returns null. */
    private static Function<Path, @Nullable String> fileReaderOf(String pathString, String contents) {
        Path expected = Path.of(pathString);
        return path -> path.equals(expected) ? contents : null;
    }
}
