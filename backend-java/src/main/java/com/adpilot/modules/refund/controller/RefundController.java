package com.adpilot.modules.refund.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.refund.dto.RefundDto;
import com.adpilot.modules.refund.service.RefundService;
import com.adpilot.modules.refund.vo.RefundVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    /**
     * GET /api/refunds - List refunds with pagination.
     */
    @GetMapping
    @RequirePermission("order:view")
    public ApiResponse<PageResponse<RefundVo>> listRefunds(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<RefundVo> result = refundService.listRefunds(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/refunds/{id} - Get refund by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("order:view")
    public ApiResponse<RefundVo> getRefund(@PathVariable String id) {
        RefundVo refundVo = refundService.getRefundById(id);
        return ApiResponse.ok(refundVo);
    }

    /**
     * POST /api/refunds/import - Import refunds in batch.
     */
    @PostMapping("/import")
    @RequirePermission("order:import")
    public ApiResponse<List<RefundVo>> importRefunds(@Valid @RequestBody List<RefundDto> dtos) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        List<RefundVo> results = refundService.importRefunds(dtos, userId);
        return ApiResponse.ok(results);
    }
}
