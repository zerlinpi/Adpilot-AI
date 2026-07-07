package com.adpilot.modules.advertising.support;

/**
 * A single {@code (id, value)} pair read from one legacy goal-type / hosting-goal column during
 * migration: the record's primary key and its current stored value.
 *
 * @param id    the {@code char(36)} primary key of the record, as a string
 * @param value the current stored goal-type / hosting-goal value (may be {@code null})
 */
public record OptimizationGoalValueRow(String id, String value) {
}
