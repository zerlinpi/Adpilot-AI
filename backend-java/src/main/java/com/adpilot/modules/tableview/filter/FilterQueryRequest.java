package com.adpilot.modules.tableview.filter;

import lombok.Data;

import java.util.List;

/**
 * Request body for the reusable {@code POST /api/{resource}/query} endpoint
 * (Req 2.9). Carries the advanced-filtering conditions plus sort and pagination.
 *
 * <p>{@code sortField} is a logical field name validated against the resource's
 * {@link FilterMetadata} (never used as a raw column), and {@code sortDir} is
 * {@code "asc"} or {@code "desc"} (defaulting to ascending).</p>
 */
@Data
public class FilterQueryRequest {

    /** Advanced-filtering conditions, combined with AND. May be null/empty. */
    private List<FilterCondition> filters;

    /** Logical field name to sort by; optional. */
    private String sortField;

    /** Sort direction: {@code asc} (default) or {@code desc}. */
    private String sortDir;

    /** 1-based page number; defaults to 1 when null or &lt; 1. */
    private Integer page;

    /** Page size; defaults to 20 when null or &lt; 1. */
    private Integer pageSize;

    public int resolvedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int resolvedPageSize() {
        return pageSize == null || pageSize < 1 ? 20 : pageSize;
    }

    public boolean descending() {
        return sortDir != null && sortDir.trim().equalsIgnoreCase("desc");
    }
}
