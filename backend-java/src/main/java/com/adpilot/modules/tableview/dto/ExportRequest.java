package com.adpilot.modules.tableview.dto;

import com.adpilot.modules.tableview.filter.FilterCondition;
import lombok.Data;

import java.util.List;

/**
 * Request body for the server-side CSV export endpoint
 * {@code POST /api/{resource}/export} (Req 2.8).
 *
 * <p>The export produces a {@code text/csv} response over the <em>full</em>
 * filtered, sorted result set (every page, not only the rendered page),
 * restricted to {@link #visibleColumns} rendered in the order supplied.
 *
 * <ul>
 *   <li>{@link #filters} — the active {@link FilterCondition}s, combined with AND
 *       and validated/translated by the shared
 *       {@link com.adpilot.modules.tableview.filter.FilterTranslator} (the same
 *       component the {@code /query} endpoint, task 16.1, uses).</li>
 *   <li>{@link #sortField} / {@link #sortDir} — the active sort, applied before
 *       paging so the CSV row order matches what the table shows; aligned with
 *       {@link com.adpilot.modules.tableview.filter.FilterQueryRequest}.</li>
 *   <li>{@link #visibleColumns} — the logical column keys to emit, in order; at
 *       least one is required and each must be a known column for the resource.</li>
 * </ul>
 */
@Data
public class ExportRequest {

    /** Active advanced-filter conditions; {@code null}/empty means no filtering. */
    private List<FilterCondition> filters;

    /** Logical field name to sort by; optional (defaults to the resource order). */
    private String sortField;

    /** Sort direction: {@code asc} (default) or {@code desc}. */
    private String sortDir;

    /** Visible column keys in their configured order; required and non-empty. */
    private List<String> visibleColumns;

    /** {@code true} when the requested sort is descending. */
    public boolean descending() {
        return sortDir != null && "desc".equalsIgnoreCase(sortDir.trim());
    }
}
