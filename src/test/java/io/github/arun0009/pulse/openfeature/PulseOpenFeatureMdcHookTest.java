package io.github.arun0009.pulse.openfeature;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PulseOpenFeatureMdcHookTest {

    private final PulseOpenFeatureMdcHook hook = new PulseOpenFeatureMdcHook();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void afterPutsValueOnMdc() {
        HookContext<Object> ctx = sampleCtx("dark_mode", false);
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("dark_mode")
                .value(true)
                .variant("on")
                .reason(Reason.STATIC.name())
                .flagMetadata(ImmutableMetadata.builder().build())
                .build();

        hook.after(ctx, details, new HashMap<>());

        assertThat(MDC.get("feature_flag.dark_mode")).isEqualTo("true");
    }

    @Test
    void finallyRemovesMdcKey() {
        HookContext<Object> ctx = sampleCtx("flag", "default");
        FlagEvaluationDetails<Object> details = FlagEvaluationDetails.<Object>builder()
                .flagKey("flag")
                .value(null)
                .build();

        hook.after(ctx, details, new HashMap<>());
        assertThat(MDC.get("feature_flag.flag")).isEqualTo("null");

        hook.finallyAfter(ctx, details, new HashMap<>());
        assertThat(MDC.get("feature_flag.flag")).isNull();
    }

    @Test
    void supportsAllFlagValueTypes() {
        for (FlagValueType type :
                List.of(FlagValueType.BOOLEAN, FlagValueType.STRING, FlagValueType.INTEGER, FlagValueType.OBJECT)) {
            assertThat(hook.supportsFlagValueType(type)).isTrue();
        }
    }

    // OpenFeature SDK 1.20 deprecated both HookContext.from() and HookContext.builder() without
    // providing a public replacement (SharedHookContext is package-private). Suppress until the
    // SDK stabilises a public test-construction API.
    @SuppressWarnings("deprecation")
    private HookContext<Object> sampleCtx(String key, Object defaultValue) {
        return HookContext.<Object>builder()
                .flagKey(key)
                .type(FlagValueType.OBJECT)
                .clientMetadata(() -> "test-client")
                .providerMetadata(() -> "test-provider")
                .ctx(new MutableContext())
                .defaultValue(defaultValue)
                .build();
    }
}
