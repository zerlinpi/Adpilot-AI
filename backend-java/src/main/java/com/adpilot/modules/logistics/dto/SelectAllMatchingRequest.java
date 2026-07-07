package com.adpilot.modules.logistics.dto;

import com.adpilot.modules.tableview.filter.FilterCondition;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for "select all matching the current filter" (Req 2.7). Carries the
 * active advanced-filter descriptor so the backend can resolve every record that
 * satisfies the filter across all pages, rather than only the rows rendered on the
 * current page. An empty/absent {@code filters} list selects every in-scope record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectAllMatchingRequest {

    /** Active advanced-filter conditions defining the matching set. */
    private List<FilterCondition> filters;
}
