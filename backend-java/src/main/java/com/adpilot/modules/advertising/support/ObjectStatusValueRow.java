package com.adpilot.modules.advertising.support;

/**
 * A single {@code (id, value)} pair read from one Object_Status column during migration: the
 * record's primary key and its current stored status value.
 *
 * @param id    the {@code char(36)} primary key of the record, as a string
 * @param value the current stored Object_Status value (may be {@code null})
 */
public record ObjectStatusValueRow(String id, String value) {
}
