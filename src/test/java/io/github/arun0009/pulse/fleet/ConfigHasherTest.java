package io.github.arun0009.pulse.fleet;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigHasherTest {

    record Sample(String a, int b, List<String> c) {}

    @Test
    void identicalTreesProduceSameHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("k", 1);
        a.put("nested", new Sample("x", 7, List.of("p", "q")));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nested", new Sample("x", 7, List.of("p", "q")));
        b.put("k", 1);

        assertThat(ConfigHasher.hash(a)).isEqualTo(ConfigHasher.hash(b));
    }

    @Test
    void anyChangeChangesHash() {
        Map<String, Object> a = Map.of("k", "v1");
        Map<String, Object> b = Map.of("k", "v2");
        assertThat(ConfigHasher.hash(a)).isNotEqualTo(ConfigHasher.hash(b));
    }

    @Test
    void recordsAreCanonicalized() {
        assertThat(ConfigHasher.hash(new Sample("x", 1, List.of("a"))))
                .isEqualTo(ConfigHasher.hash(new Sample("x", 1, List.of("a"))));
        assertThat(ConfigHasher.hash(new Sample("x", 1, List.of("a"))))
                .isNotEqualTo(ConfigHasher.hash(new Sample("x", 1, List.of("b"))));
    }

    @Test
    void renderProducesStableLines() {
        List<String> lines = ConfigHasher.render(Map.of("k", "v", "nested", new Sample("x", 7, List.of("p", "q"))));
        assertThat(lines).contains("k = v", "nested.a = x", "nested.b = 7", "nested.c[0] = p", "nested.c[1] = q");
    }
}
