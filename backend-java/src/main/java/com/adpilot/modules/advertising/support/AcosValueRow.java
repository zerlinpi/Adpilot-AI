package com.adpilot.modules.advertising.support;

import java.math.BigDecimal;

/**
 * A single {@code (id, value)} pair read from one historical ACoS column during migration: the
 * record's primary key and its current stored ACoS value.
 *
 * @param id    the {@code char(36)} primary key of the record, as a string
 * @param value the current stored ACoS value (may be {@code null})
 */
public record AcosValueRow(String id, BigDecimal value) {
}
