package com.adpilot.modules.profit.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.profit.service.ProfitService;
import com.adpilot.modules.profit.vo.ProfitAttributionVo;
import com.adpilot.modules.profit.vo.ProfitDashboardVo;
import com.adpilot.modules.profit.vo.ProductProfitVo;
import com.adpilot.modules.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/profit")
@RequiredArgsConstructor
public class ProfitController {

    private final ProfitService profitService;
    private final StoreService storeService;

    @GetMapping("/dashboard")
    @RequirePermission("finance:view")
    public ApiResponse<ProfitDashboardVo> getDashboard(
            @RequestParam String storeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        storeService.getStoreById(storeId);
        return ApiResponse.ok(profitService.getProfitDashboard(storeId, startDate, endDate));
    }

    @GetMapping("/products")
    @RequirePermission("finance:view")
    public ApiResponse<List<ProductProfitVo>> getProducts(
            @RequestParam String storeId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        storeService.getStoreById(storeId);
        return ApiResponse.ok(profitService.getProductProfits(storeId, startDate, endDate));
    }

    @GetMapping("/attribution")
    @RequirePermission("finance:view")
    public ApiResponse<List<ProfitAttributionVo>> getAttribution(
            @RequestParam String storeId) {
        storeService.getStoreById(storeId);
        return ApiResponse.ok(profitService.getProfitAttribution(storeId));
    }
}
