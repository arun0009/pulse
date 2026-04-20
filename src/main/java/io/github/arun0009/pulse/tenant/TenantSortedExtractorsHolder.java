package io.github.arun0009.pulse.tenant;

import java.util.List;

/**
 * Tiny holder so code that wants the resolved tenant-extractor order can inject one bean
 * instead of a {@code List<TenantExtractor>}. Built by
 * {@code PulseTenantConfiguration.pulseTenantSortedExtractors} using
 * {@code ObjectProvider.orderedStream()}, which honors {@code @Order} / {@code Ordered}.
 */
public record TenantSortedExtractorsHolder(List<TenantExtractor> extractors) {}
