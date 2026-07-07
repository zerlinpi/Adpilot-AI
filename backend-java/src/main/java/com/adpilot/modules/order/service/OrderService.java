package com.adpilot.modules.order.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.order.dto.OrderDto;
import com.adpilot.modules.order.vo.OrderVo;

public interface OrderService {

    /**
     * List orders with pagination.
     */
    PageResponse<OrderVo> listOrders(String storeId, int page, int pageSize);

    /**
     * Get order by ID.
     */
    OrderVo getOrderById(String id);

    /**
     * Create a new order.
     */
    OrderVo createOrder(OrderDto dto, String userId);

    /**
     * Update an existing order.
     */
    OrderVo updateOrder(String id, OrderDto dto, String userId);

    /**
     * Delete an order by ID.
     */
    void deleteOrder(String id);

    /**
     * List returns with pagination.
     */
    PageResponse<Object> listReturns(String storeId, int page, int pageSize);

    /**
     * List refunds with pagination.
     */
    PageResponse<Object> listRefunds(String storeId, int page, int pageSize);

    /**
     * List settlements with pagination.
     */
    PageResponse<Object> listSettlements(String storeId, int page, int pageSize);

    /**
     * List buyer messages with pagination.
     */
    PageResponse<Object> listBuyerMessages(String storeId, int page, int pageSize);
}
