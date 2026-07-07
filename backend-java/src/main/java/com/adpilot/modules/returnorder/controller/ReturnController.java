package com.adpilot.modules.returnorder.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.returnorder.dto.ReturnDto;
import com.adpilot.modules.returnorder.service.ReturnService;
import com.adpilot.modules.returnorder.vo.ReturnVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/returns")
@RequiredArgsConstructor
public class ReturnController {

    private final ReturnService returnService;

    /**
     * GET /api/returns - List returns with pagination.
     */
    @GetMapping
    @RequirePermission("order:view")
    public ApiResponse<PageResponse<ReturnVo>> listReturns(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<ReturnVo> result = returnService.listReturns(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/returns/{id} - Get return by ID.
     */
    @GetMapping("/{id}")
    @RequirePermission("order:view")
    public ApiResponse<ReturnVo> getReturn(@PathVariable String id) {
        ReturnVo returnVo = returnService.getReturnById(id);
        return ApiResponse.ok(returnVo);
    }

    /**
     * POST /api/returns/import - Import returns in batch.
     */
    @PostMapping("/import")
    @RequirePermission("order:import")
    public ApiResponse<List<ReturnVo>> importReturns(@Valid @RequestBody List<ReturnDto> dtos) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        List<ReturnVo> results = returnService.importReturns(dtos, userId);
        return ApiResponse.ok(results);
    }
}
