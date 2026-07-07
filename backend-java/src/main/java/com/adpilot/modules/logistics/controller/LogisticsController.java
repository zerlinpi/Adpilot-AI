package com.adpilot.modules.logistics.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.dto.CarrierDto;
import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.dto.CustomsClearanceDto;
import com.adpilot.modules.logistics.dto.FbaFieldsDto;
import com.adpilot.modules.logistics.dto.HandlingCostDto;
import com.adpilot.modules.logistics.dto.SelectAllMatchingRequest;
import com.adpilot.modules.logistics.dto.ShipmentDto;
import com.adpilot.modules.logistics.dto.ShipmentExceptionDto;
import com.adpilot.modules.logistics.dto.ShipmentLegDto;
import com.adpilot.modules.logistics.dto.TrackingEventDto;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentExceptionEntity;
import com.adpilot.modules.logistics.mapper.ShipmentExceptionMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.CarrierService;
import com.adpilot.modules.logistics.service.CartonSpecService;
import com.adpilot.modules.logistics.service.CostChainService;
import com.adpilot.modules.logistics.service.CustomsClearanceService;
import com.adpilot.modules.logistics.service.FbaShipmentService;
import com.adpilot.modules.logistics.service.HandlingCostService;
import com.adpilot.modules.logistics.service.LogisticsService;
import com.adpilot.modules.logistics.service.ShipmentExceptionService;
import com.adpilot.modules.logistics.service.ShipmentLegService;
import com.adpilot.modules.logistics.service.TrackingEventService;
import com.adpilot.modules.logistics.vo.CarrierVo;
import com.adpilot.modules.logistics.vo.CartonSpecVo;
import com.adpilot.modules.logistics.vo.CartonTotalsVo;
import com.adpilot.modules.logistics.vo.CostChainVo;
import com.adpilot.modules.logistics.vo.CustomsClearanceVo;
import com.adpilot.modules.logistics.vo.FbaFieldsVo;
import com.adpilot.modules.logistics.vo.HandlingCostVo;
import com.adpilot.modules.logistics.vo.ShipmentExceptionVo;
import com.adpilot.modules.logistics.vo.ShipmentLegVo;
import com.adpilot.modules.logistics.vo.ShipmentVo;
import com.adpilot.modules.logistics.vo.TrackingEventVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the logistics / FBA shipment domain (Req 4–10, 16, 18).
 *
 * <p>Every read or write that targets a shipment resolves and enforces the
 * requester's effective data scope through {@link DataScopeService}
 * ({@code assertCanRead} before returning shipment-scoped data,
 * {@code assertCanWrite} before mutating it). An unknown shipment or one outside
 * the requester's scope yields a not-found / denied result with <em>no</em>
 * record data (Req 10.8, 16.6, 17.1–17.4). Carriers are org-scoped and that
 * scope is enforced inside {@link CarrierService}; exception listing is
 * store-scoped inside {@link ShipmentExceptionService#listForStore(String)}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/logistics")
@RequiredArgsConstructor
public class LogisticsController {

    private final LogisticsService logisticsService;
    private final CarrierService carrierService;
    private final ShipmentLegService shipmentLegService;
    private final CartonSpecService cartonSpecService;
    private final CustomsClearanceService customsClearanceService;
    private final TrackingEventService trackingEventService;
    private final ShipmentExceptionService shipmentExceptionService;
    private final HandlingCostService handlingCostService;
    private final FbaShipmentService fbaShipmentService;
    private final CostChainService costChainService;

    private final ShipmentMapper shipmentMapper;
    private final ShipmentExceptionMapper shipmentExceptionMapper;
    private final DataScopeService dataScopeService;

    // ------------------------------------------------------------------
    // Shipments (existing surface)
    // ------------------------------------------------------------------

    /**
     * GET /api/logistics/shipments - List shipments with pagination.
     */
    @GetMapping("/shipments")
    @RequirePermission("warehouse:view")
    public ApiResponse<PageResponse<ShipmentVo>> listShipments(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ShipmentVo> result = logisticsService.listShipments(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/logistics/shipments/{id} - Get shipment by ID.
     */
    @GetMapping("/shipments/{id}")
    @RequirePermission("warehouse:view")
    public ApiResponse<ShipmentVo> getShipment(@PathVariable String id) {
        ShipmentVo shipment = logisticsService.getShipmentById(id);
        return ApiResponse.ok(shipment);
    }

    /**
     * POST /api/logistics/shipments - Create a new shipment.
     */
    @PostMapping("/shipments")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ShipmentVo> createShipment(@Valid @RequestBody ShipmentDto dto) {
        String userId = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUserId() : null;
        ShipmentVo shipment = logisticsService.createShipment(dto, userId);
        return ApiResponse.ok(shipment);
    }

    /**
     * PUT /api/logistics/shipments/{id}/status - Update shipment status.
     */
    @PutMapping("/shipments/{id}/status")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ShipmentVo> updateShipmentStatus(
            @PathVariable String id,
            @RequestParam String status) {
        String userId = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUserId() : null;
        ShipmentVo shipment = logisticsService.updateShipmentStatus(id, status, userId);
        return ApiResponse.ok(shipment);
    }

    /**
     * POST /api/logistics/shipments/query - Query shipments with server-side
     * advanced filtering, sort, and pagination (Req 2.9, 2.10).
     *
     * <p>The body carries advanced-filter conditions that are validated (field
     * exists, operator valid for the field type, value type matches) and then
     * combined with AND on top of the requester's data scope. An invalid
     * condition is rejected with a {@code FILTER_INVALID} error identifying which
     * part is invalid, leaving the caller's current rows unchanged.</p>
     */
    @PostMapping("/shipments/query")
    @RequirePermission("warehouse:view")
    public ApiResponse<PageResponse<ShipmentVo>> queryShipments(
            @RequestBody(required = false) com.adpilot.modules.tableview.filter.FilterQueryRequest request) {
        PageResponse<ShipmentVo> result = logisticsService.queryShipments(request);
        return ApiResponse.ok(result);
    }

    /**
     * POST /api/logistics/shipments/select-all - Resolve "select all matching the
     * current filter" (Req 2.7).
     *
     * <p>Given the active advanced-filter descriptor, returns the identifiers of
     * every shipment satisfying the filter across all pages — not only the rows
     * rendered on the current page — so a subsequent bulk operation targets the
     * full matching set. The resolution is restricted to the requester's data
     * scope, identical to the list / query / export endpoints, and invalid filter
     * conditions are rejected without resolving any identifiers.</p>
     */
    @PostMapping("/shipments/select-all")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<String>> selectAllMatchingShipments(
            @RequestBody(required = false) SelectAllMatchingRequest request) {
        List<com.adpilot.modules.tableview.filter.FilterCondition> filters =
                request != null ? request.getFilters() : null;
        List<String> ids = logisticsService.selectAllMatching(filters);
        return ApiResponse.ok(ids);
    }

    // ------------------------------------------------------------------
    // Carriers (org-scoped — scope enforced inside CarrierService)
    // ------------------------------------------------------------------

    /** GET /api/logistics/carriers - List the requester's org-scoped carriers (Req 6.4, 17.1). */
    @GetMapping("/carriers")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<CarrierVo>> listCarriers() {
        return ApiResponse.ok(carrierService.list());
    }

    /** POST /api/logistics/carriers - Create an org-scoped carrier (Req 6.1). */
    @PostMapping("/carriers")
    @RequirePermission("warehouse:manage")
    public ApiResponse<CarrierVo> createCarrier(@Valid @RequestBody CarrierDto dto) {
        return ApiResponse.ok(carrierService.create(dto));
    }

    /** PUT /api/logistics/carriers/{id} - Update an org-scoped carrier (Req 6.2, 17.1). */
    @PutMapping("/carriers/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<CarrierVo> updateCarrier(@PathVariable String id,
                                                @Valid @RequestBody CarrierDto dto) {
        return ApiResponse.ok(carrierService.update(id, dto));
    }

    // ------------------------------------------------------------------
    // Shipment legs (Req 4)
    // ------------------------------------------------------------------

    /** POST /api/logistics/shipments/{shipmentId}/legs - Upsert a leg (Req 4.3). */
    @PostMapping("/shipments/{shipmentId}/legs")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ShipmentLegVo> upsertLeg(@PathVariable String shipmentId,
                                                @Valid @RequestBody ShipmentLegDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(shipmentLegService.upsertLeg(shipmentId, dto));
    }

    /** GET /api/logistics/shipments/{shipmentId}/legs - List legs ordered by sequence (Req 4.4). */
    @GetMapping("/shipments/{shipmentId}/legs")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<ShipmentLegVo>> listLegs(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(shipmentLegService.listLegs(shipmentId));
    }

    // ------------------------------------------------------------------
    // Carton specs (Req 5)
    // ------------------------------------------------------------------

    /** POST /api/logistics/shipments/{shipmentId}/cartons - Add a carton spec (Req 5.2). */
    @PostMapping("/shipments/{shipmentId}/cartons")
    @RequirePermission("warehouse:manage")
    public ApiResponse<CartonSpecVo> addCartonSpec(@PathVariable String shipmentId,
                                                   @Valid @RequestBody CartonSpecDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(cartonSpecService.addSpec(shipmentId, dto));
    }

    /** GET /api/logistics/shipments/{shipmentId}/cartons/totals - Compute carton totals (Req 5.3, 5.4). */
    @GetMapping("/shipments/{shipmentId}/cartons/totals")
    @RequirePermission("warehouse:view")
    public ApiResponse<CartonTotalsVo> cartonTotals(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(cartonSpecService.totals(shipmentId));
    }

    // ------------------------------------------------------------------
    // Customs clearance (Req 7)
    // ------------------------------------------------------------------

    /** GET /api/logistics/shipments/{shipmentId}/customs - Get clearance, not-started when absent (Req 7.2, 7.6). */
    @GetMapping("/shipments/{shipmentId}/customs")
    @RequirePermission("warehouse:view")
    public ApiResponse<CustomsClearanceVo> getCustoms(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(customsClearanceService.get(shipmentId));
    }

    /** PUT /api/logistics/shipments/{shipmentId}/customs - Update clearance (Req 7.2). */
    @PutMapping("/shipments/{shipmentId}/customs")
    @RequirePermission("warehouse:manage")
    public ApiResponse<CustomsClearanceVo> updateCustoms(@PathVariable String shipmentId,
                                                         @Valid @RequestBody CustomsClearanceDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(customsClearanceService.update(shipmentId, dto));
    }

    // ------------------------------------------------------------------
    // Tracking events (Req 8)
    // ------------------------------------------------------------------

    /** POST /api/logistics/shipments/{shipmentId}/tracking - Add a tracking event (Req 8.4). */
    @PostMapping("/shipments/{shipmentId}/tracking")
    @RequirePermission("warehouse:manage")
    public ApiResponse<TrackingEventVo> addTrackingEvent(@PathVariable String shipmentId,
                                                         @Valid @RequestBody TrackingEventDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(trackingEventService.add(shipmentId, dto));
    }

    /** GET /api/logistics/shipments/{shipmentId}/tracking - List events most-recent first (Req 8.4). */
    @GetMapping("/shipments/{shipmentId}/tracking")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<TrackingEventVo>> listTrackingEvents(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(trackingEventService.list(shipmentId));
    }

    // ------------------------------------------------------------------
    // Shipment exceptions (Req 9)
    // ------------------------------------------------------------------

    /** POST /api/logistics/shipments/{shipmentId}/exceptions - Raise an exception (Req 9.2). */
    @PostMapping("/shipments/{shipmentId}/exceptions")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ShipmentExceptionVo> raiseException(@PathVariable String shipmentId,
                                                           @Valid @RequestBody ShipmentExceptionDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(shipmentExceptionService.raise(shipmentId, dto));
    }

    /** POST /api/logistics/exceptions/{exceptionId}/resolve - Resolve an exception (Req 9.4). */
    @PostMapping("/exceptions/{exceptionId}/resolve")
    @RequirePermission("warehouse:manage")
    public ApiResponse<ShipmentExceptionVo> resolveException(@PathVariable String exceptionId) {
        // Resolve the owning shipment so the write can be scope-checked; an unknown
        // exception or one outside the requester's scope yields not-found / denied
        // with no record data (Req 9.7, 17.x).
        ShipmentExceptionEntity exception = loadException(exceptionId);
        assertShipmentWritableById(exception.getShipmentId());
        return ApiResponse.ok(shipmentExceptionService.resolve(exceptionId));
    }

    /** GET /api/logistics/exceptions - List exceptions for the requester's Active_Store (Req 9.7). */
    @GetMapping("/exceptions")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<ShipmentExceptionVo>> listExceptions(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(shipmentExceptionService.listForStore(storeId));
    }

    // ------------------------------------------------------------------
    // Handling costs (Req 18)
    // ------------------------------------------------------------------

    /** POST /api/logistics/shipments/{shipmentId}/handling-costs - Create a handling-cost line (Req 18.2). */
    @PostMapping("/shipments/{shipmentId}/handling-costs")
    @RequirePermission("warehouse:manage")
    public ApiResponse<HandlingCostVo> createHandlingCost(@PathVariable String shipmentId,
                                                          @Valid @RequestBody HandlingCostDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(handlingCostService.upsert(shipmentId, null, dto));
    }

    /** PUT /api/logistics/shipments/{shipmentId}/handling-costs/{id} - Update a handling-cost line (Req 18.2). */
    @PutMapping("/shipments/{shipmentId}/handling-costs/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<HandlingCostVo> updateHandlingCost(@PathVariable String shipmentId,
                                                          @PathVariable String id,
                                                          @Valid @RequestBody HandlingCostDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(handlingCostService.upsert(shipmentId, id, dto));
    }

    /** DELETE /api/logistics/shipments/{shipmentId}/handling-costs/{id} - Delete a handling-cost line. */
    @DeleteMapping("/shipments/{shipmentId}/handling-costs/{id}")
    @RequirePermission("warehouse:manage")
    public ApiResponse<Void> deleteHandlingCost(@PathVariable String shipmentId,
                                                @PathVariable String id) {
        assertShipmentWritable(shipmentId);
        handlingCostService.delete(id);
        return ApiResponse.ok(null);
    }

    /** GET /api/logistics/shipments/{shipmentId}/handling-costs - List handling-cost lines (Req 18.3). */
    @GetMapping("/shipments/{shipmentId}/handling-costs")
    @RequirePermission("warehouse:view")
    public ApiResponse<List<HandlingCostVo>> listHandlingCosts(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(handlingCostService.list(shipmentId));
    }

    // ------------------------------------------------------------------
    // FBA core fields (Req 16)
    // ------------------------------------------------------------------

    /** GET /api/logistics/shipments/{shipmentId}/fba - Read FBA fields + line items (Req 16.6). */
    @GetMapping("/shipments/{shipmentId}/fba")
    @RequirePermission("warehouse:view")
    public ApiResponse<FbaFieldsVo> getFbaFields(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(fbaShipmentService.getFields(shipmentId));
    }

    /** PUT /api/logistics/shipments/{shipmentId}/fba - Save FBA fields + line items (Req 16.3). */
    @PutMapping("/shipments/{shipmentId}/fba")
    @RequirePermission("warehouse:manage")
    public ApiResponse<FbaFieldsVo> saveFbaFields(@PathVariable String shipmentId,
                                                  @Valid @RequestBody FbaFieldsDto dto) {
        assertShipmentWritable(shipmentId);
        return ApiResponse.ok(fbaShipmentService.saveFields(shipmentId, dto));
    }

    // NOTE: the cost-chain endpoint reuses assertShipmentReadable(shipmentId)
    // for scope enforcement (Req 10.2, 10.8); CostChainService itself also
    // re-checks not-found / denied for unknown or out-of-scope shipments.

    /** GET /api/logistics/shipments/{shipmentId}/cost-chain - Itemized cost chain + total (Req 10.2, 10.3). */
    @GetMapping("/shipments/{shipmentId}/cost-chain")
    @RequirePermission("warehouse:view")
    public ApiResponse<CostChainVo> getCostChain(@PathVariable String shipmentId) {
        assertShipmentReadable(shipmentId);
        return ApiResponse.ok(costChainService.compute(shipmentId));
    }

    // ------------------------------------------------------------------
    // Scope-enforcement helpers
    // ------------------------------------------------------------------

    /**
     * Assert the requester may read the shipment, returning a not-found / denied
     * result with no record data for unknown or out-of-scope shipments.
     */
    private void assertShipmentReadable(String shipmentId) {
        ShipmentEntity shipment = loadShipment(shipmentId);
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(shipment, user);
        }
    }

    /**
     * Assert the requester may write the shipment, returning a not-found / denied
     * result with no record data for unknown or out-of-scope shipments.
     */
    private void assertShipmentWritable(String shipmentId) {
        ShipmentEntity shipment = loadShipment(shipmentId);
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(shipment, user);
        }
    }

    /** Variant that accepts an already-resolved shipment id (e.g. from an exception). */
    private void assertShipmentWritableById(UUID shipmentId) {
        ShipmentEntity shipment = shipmentId != null ? shipmentMapper.selectById(shipmentId) : null;
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found");
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(shipment, user);
        }
    }

    /** Load a shipment by id, treating a missing / malformed id as not-found. */
    private ShipmentEntity loadShipment(String shipmentId) {
        UUID id = parseShipmentId(shipmentId);
        ShipmentEntity shipment = shipmentMapper.selectById(id);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
        return shipment;
    }

    /** Load an exception by id, treating a missing / malformed id as not-found. */
    private ShipmentExceptionEntity loadException(String exceptionId) {
        UUID id;
        if (exceptionId == null || exceptionId.trim().isEmpty()) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception not found");
        }
        try {
            id = UUID.fromString(exceptionId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception not found: " + exceptionId);
        }
        ShipmentExceptionEntity exception = shipmentExceptionMapper.selectById(id);
        if (exception == null) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception not found: " + exceptionId);
        }
        return exception;
    }

    private UUID parseShipmentId(String shipmentId) {
        if (shipmentId == null || shipmentId.trim().isEmpty()) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment id is required");
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
    }

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }
}
