package com.adpilot.modules.logistics.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.logistics.dto.ShipmentDto;
import com.adpilot.modules.logistics.vo.ShipmentVo;
import com.adpilot.modules.tableview.filter.FilterCondition;
import com.adpilot.modules.tableview.filter.FilterQueryRequest;

import java.util.List;

public interface LogisticsService {

    /**
     * List shipments with pagination.
     */
    PageResponse<ShipmentVo> listShipments(String storeId, int page, int pageSize);

    /**
     * Query shipments with server-side advanced filtering (Req 2.9, 2.10).
     *
     * <p>Each {@code FilterCondition} is validated (field exists, operator valid
     * for the field type, value type matches); an invalid condition is rejected
     * with a {@code FILTER_INVALID} {@code BusinessException} identifying which
     * part is invalid, and no rows are returned or changed. Valid conditions are
     * combined with AND and applied on top of the requester's data scope, then
     * sorted and paged.</p>
     *
     * @param request the filters, sort, and pagination
     * @return the page of matching shipments, scoped to the requester
     */
    PageResponse<ShipmentVo> queryShipments(FilterQueryRequest request);

    /**
     * Resolve "select all matching the current filter" (Req 2.7).
     *
     * <p>Given the active advanced-filter descriptor, return the identifiers of
     * every shipment that satisfies the filter across all pages (not only the
     * rendered page), restricted to the requester's data scope. An empty/absent
     * filter selects every in-scope shipment. Invalid filter conditions are
     * rejected without resolving any identifiers.</p>
     *
     * @param filters the active advanced-filter conditions (may be {@code null}/empty)
     * @return the matching shipment ids across all pages, scoped to the requester
     */
    List<String> selectAllMatching(List<FilterCondition> filters);

    /**
     * Get shipment by ID.
     */
    ShipmentVo getShipmentById(String id);

    /**
     * Create a new shipment.
     */
    ShipmentVo createShipment(ShipmentDto dto, String userId);

    /**
     * Update shipment status.
     */
    ShipmentVo updateShipmentStatus(String id, String status, String userId);
}
