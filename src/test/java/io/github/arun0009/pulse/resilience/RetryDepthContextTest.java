package io.github.arun0009.pulse.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDepthContextTest {

    @AfterEach
    void cleanup() {
        RetryDepthContext.clear();
    }

    @Test
    void defaultsToZero() {
        assertThat(RetryDepthContext.current()).isZero();
    }

    @Test
    void incrementBumpsAndReturnsNewValue() {
        assertThat(RetryDepthContext.increment()).isEqualTo(1);
        assertThat(RetryDepthContext.increment()).isEqualTo(2);
        assertThat(RetryDepthContext.current()).isEqualTo(2);
    }

    @Test
    void setNullClears() {
        RetryDepthContext.set(7);
        RetryDepthContext.set(null);
        assertThat(RetryDepthContext.current()).isZero();
    }

    @Test
    void clearRemovesValue() {
        RetryDepthContext.set(5);
        RetryDepthContext.clear();
        assertThat(RetryDepthContext.current()).isZero();
    }
}
