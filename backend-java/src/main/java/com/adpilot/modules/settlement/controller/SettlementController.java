package com.adpilot.modules.settlement.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.settlement.dto.SettlementDto;
import com.adpilot.modules.settlement.service.SettlementService;
import com.adpilot.modules.settlement.vo.SettlementVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;

    /**
     * GET /api/settlements - List settlements with pagination.
     */
    @GetMapping
    @RequirePermission("finance:view")
    public ApiResponse<PageResponse<SettlementVo>> listSettlements(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<SettlementVo> result = settlementService.listSettlements(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/settlements/{id} - Get settlement by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("finance:view")
    public ApiResponse<SettlementVo> getSettlement(@PathVariable String id) {
        SettlementVo settlement = settlementService.getSettlementById(id);
        return ApiResponse.ok(settlement);
    }

    /**
     * POST /api/settlements/import - Import a settlement record.
     */
    @PostMapping("/import")
    @RequirePermission("finance:manage")
    public ApiResponse<SettlementVo> importSettlement(@Valid @RequestBody SettlementDto dto) {
        SettlementVo settlement = settlementService.importSettlement(dto);
        return ApiResponse.ok(settlement);
    }
}
