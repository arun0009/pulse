/**
 * Pulse — production-correctness layer for Spring Boot.
 *
 * <p>Every package under this root is {@code @NullMarked}; non-null is the default and any
 * nullable type must be annotated with {@code @Nullable}. NullAway enforces this at compile time
 * (see the project pom for configuration).
 */
@NullMarked
package io.github.arun0009.pulse;

import org.jspecify.annotations.NullMarked;
