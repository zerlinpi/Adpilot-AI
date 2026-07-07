package com.adpilot.modules.tableview.filter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Specification for one filterable field of a resource: the client-facing logical
 * field name, the physical DB column it maps to, its {@link FilterFieldType}, and
 * (optionally, for {@link FilterFieldType#ENUM} fields) the set of allowed values.
 *
 * <p>The physical {@code column} is the <em>only</em> source of column names used
 * to build a query; client-supplied field names are matched against specs and
 * never interpolated into SQL, so the registry of specs also serves as an
 * allow-list that prevents column injection (Req 2.9, 2.10).</p>
 *
 * <p>Each resource exposing {@code POST /api/{resource}/query} declares a registry
 * via {@link #registry(FilterFieldSpec...)} that the shared {@link FilterValidator}
 * and {@link FilterTranslator} consult, keeping filtering reusable and
 * resource-agnostic.</p>
 */
public final class FilterFieldSpec {

    private final String field;
    private final String column;
    private final FilterFieldType type;
    private final Set<String> enumOptions;

    private FilterFieldSpec(String field, String column, FilterFieldType type, Set<String> enumOptions) {
        this.field = field;
        this.column = column;
        this.type = type;
        this.enumOptions = enumOptions == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(enumOptions));
    }

    /**
     * Create a spec for {@code field} mapped to {@code column} of {@code type}.
     * For an {@link FilterFieldType#ENUM} field declared this way, any string
     * value is accepted (no fixed option set); use
     * {@link #ofEnum(String, String, Set)} to restrict to specific values.
     */
    public static FilterFieldSpec of(String field, String column, FilterFieldType type) {
        return new FilterFieldSpec(field, column, type, Collections.emptySet());
    }

    /**
     * Create an {@link FilterFieldType#ENUM} spec restricted to {@code options}.
     */
    public static FilterFieldSpec ofEnum(String field, String column, Set<String> options) {
        return new FilterFieldSpec(field, column, FilterFieldType.ENUM, options);
    }

    /**
     * Build an ordered registry keyed by logical field name from the given specs.
     */
    public static Map<String, FilterFieldSpec> registry(FilterFieldSpec... specs) {
        Map<String, FilterFieldSpec> map = new LinkedHashMap<>();
        if (specs != null) {
            for (FilterFieldSpec spec : specs) {
                map.put(spec.field, spec);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public String field() {
        return field;
    }

    public String column() {
        return column;
    }

    public FilterFieldType type() {
        return type;
    }

    /** The allowed values for a restricted ENUM field; empty otherwise. */
    public Set<String> enumOptions() {
        return enumOptions;
    }
}
