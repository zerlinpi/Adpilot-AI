package com.adpilot.modules.order.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.order.dto.OrderDto;
import com.adpilot.modules.order.service.OrderService;
import com.adpilot.modules.order.vo.OrderVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * GET /api/orders - List orders with pagination.
     */
    @GetMapping
    public ApiResponse<PageResponse<OrderVo>> listOrders(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<OrderVo> result = orderService.listOrders(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/orders/{id} - Get order by ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<OrderVo> getOrder(@PathVariable String id) {
        OrderVo order = orderService.getOrderById(id);
        return ApiResponse.ok(order);
    }

    /**
     * POST /api/orders - Create a new order.
     */
    @PostMapping
    @RequirePermission("order:create")
    public ApiResponse<OrderVo> createOrder(@Valid @RequestBody OrderDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OrderVo order = orderService.createOrder(dto, userId);
        return ApiResponse.ok(order);
    }

    /**
     * PUT /api/orders/{id} - Update an existing order.
     */
    @PutMapping("/{id}")
    @RequirePermission("order:update")
    public ApiResponse<OrderVo> updateOrder(@PathVariable String id, @Valid @RequestBody OrderDto dto) {
        String userId = com.adpilot.common.utils.SecurityUtils.isAuthenticated() ? com.adpilot.common.utils.SecurityUtils.getCurrentUserId() : null;
        OrderVo order = orderService.updateOrder(id, dto, userId);
        return ApiResponse.ok(order);
    }

    /**
     * DELETE /api/orders/{id} - Delete an order.
     */
    @DeleteMapping("/{id}")
    @RequirePermission("order:update")
    public ApiResponse<Void> deleteOrder(@PathVariable String id) {
        orderService.deleteOrder(id);
        return ApiResponse.ok(null);
    }

    /**
     * GET /api/orders/returns - List returns with pagination.
     */
    @GetMapping("/returns")
    public ApiResponse<PageResponse<Object>> listReturns(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<Object> result = orderService.listReturns(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/orders/refunds - List refunds with pagination.
     */
    @GetMapping("/refunds")
    public ApiResponse<PageResponse<Object>> listRefunds(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<Object> result = orderService.listRefunds(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/orders/settlements - List settlements with pagination.
     */
    @GetMapping("/settlements")
    public ApiResponse<PageResponse<Object>> listSettlements(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<Object> result = orderService.listSettlements(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }

    /**
     * GET /api/orders/buyer-messages - List buyer messages with pagination.
     */
    @GetMapping("/buyer-messages")
    public ApiResponse<PageResponse<Object>> listBuyerMessages(
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<Object> result = orderService.listBuyerMessages(storeId, page, pageSize);
        return ApiResponse.ok(result);
    }
}
