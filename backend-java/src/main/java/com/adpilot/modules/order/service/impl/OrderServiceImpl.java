package com.adpilot.modules.order.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.order.dto.OrderDto;
import com.adpilot.modules.order.entity.*;
import com.adpilot.modules.order.mapper.*;
import com.adpilot.modules.returnorder.mapper.ReturnMapper;
import com.adpilot.modules.returnorder.entity.ReturnEntity;
import com.adpilot.modules.refund.mapper.RefundMapper;
import com.adpilot.modules.refund.entity.RefundEntity;
import com.adpilot.modules.settlement.mapper.SettlementMapper;
import com.adpilot.modules.settlement.entity.SettlementEntity;
import com.adpilot.modules.customer.mapper.BuyerMessageMapper;
import com.adpilot.modules.customer.entity.BuyerMessageEntity;
import com.adpilot.modules.order.service.OrderService;
import com.adpilot.modules.order.vo.OrderVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final ReturnMapper returnMapper;
    private final RefundMapper refundMapper;
    private final SettlementMapper settlementMapper;
    private final BuyerMessageMapper buyerMessageMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by order-domain entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<OrderVo> listOrders(String storeId, int page, int pageSize) {
        Page<OrderEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<OrderEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<OrderEntity> result = orderMapper.selectPage(pageParam, wrapper);
        List<OrderVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public OrderVo getOrderById(String id) {
        OrderEntity entity = orderMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public OrderVo createOrder(OrderDto dto, String userId) {
        OrderEntity entity = OrderEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .orderId(dto.getOrderId())
                .orderItemId(dto.getOrderItemId())
                .purchaseDate(dto.getPurchaseDate())
                .lastUpdateDate(dto.getLastUpdateDate())
                .orderStatus(dto.getOrderStatus())
                .fulfillmentChannel(dto.getFulfillmentChannel())
                .salesChannel(dto.getSalesChannel())
                .marketplaceId(dto.getMarketplaceId())
                .sku(dto.getSku())
                .asin(dto.getAsin())
                .productName(dto.getProductName())
                .quantityOrdered(dto.getQuantityOrdered() != null ? dto.getQuantityOrdered() : 0)
                .itemPrice(dto.getItemPrice() != null ? dto.getItemPrice() : BigDecimal.ZERO)
                .itemTax(dto.getItemTax() != null ? dto.getItemTax() : BigDecimal.ZERO)
                .shippingPrice(dto.getShippingPrice() != null ? dto.getShippingPrice() : BigDecimal.ZERO)
                .shippingTax(dto.getShippingTax() != null ? dto.getShippingTax() : BigDecimal.ZERO)
                .itemPromotionDiscount(dto.getItemPromotionDiscount() != null ? dto.getItemPromotionDiscount() : BigDecimal.ZERO)
                .shipPromotionDiscount(dto.getShipPromotionDiscount() != null ? dto.getShipPromotionDiscount() : BigDecimal.ZERO)
                .currency(dto.getCurrency())
                .buyerEmail(dto.getBuyerEmail())
                .recipientName(dto.getRecipientName())
                .shipAddressLine1(dto.getShipAddressLine1())
                .shipCity(dto.getShipCity())
                .shipState(dto.getShipState())
                .shipPostalCode(dto.getShipPostalCode())
                .shipCountry(dto.getShipCountry())
                .rawData(dto.getRawData())
                .build();

        orderMapper.insert(entity);
        log.info("Order created: id={}, orderId={}", entity.getId(), entity.getOrderId());
        return toVo(entity);
    }

    @Override
    @Transactional
    public OrderVo updateOrder(String id, OrderDto dto, String userId) {
        OrderEntity entity = orderMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        if (dto.getOrderId() != null) {
            entity.setOrderId(dto.getOrderId());
        }
        if (dto.getOrderItemId() != null) {
            entity.setOrderItemId(dto.getOrderItemId());
        }
        if (dto.getPurchaseDate() != null) {
            entity.setPurchaseDate(dto.getPurchaseDate());
        }
        if (dto.getLastUpdateDate() != null) {
            entity.setLastUpdateDate(dto.getLastUpdateDate());
        }
        if (dto.getOrderStatus() != null) {
            entity.setOrderStatus(dto.getOrderStatus());
        }
        if (dto.getFulfillmentChannel() != null) {
            entity.setFulfillmentChannel(dto.getFulfillmentChannel());
        }
        if (dto.getSalesChannel() != null) {
            entity.setSalesChannel(dto.getSalesChannel());
        }
        if (dto.getMarketplaceId() != null) {
            entity.setMarketplaceId(dto.getMarketplaceId());
        }
        if (dto.getSku() != null) {
            entity.setSku(dto.getSku());
        }
        if (dto.getAsin() != null) {
            entity.setAsin(dto.getAsin());
        }
        if (dto.getProductName() != null) {
            entity.setProductName(dto.getProductName());
        }
        if (dto.getQuantityOrdered() != null) {
            entity.setQuantityOrdered(dto.getQuantityOrdered());
        }
        if (dto.getItemPrice() != null) {
            entity.setItemPrice(dto.getItemPrice());
        }
        if (dto.getItemTax() != null) {
            entity.setItemTax(dto.getItemTax());
        }
        if (dto.getShippingPrice() != null) {
            entity.setShippingPrice(dto.getShippingPrice());
        }
        if (dto.getShippingTax() != null) {
            entity.setShippingTax(dto.getShippingTax());
        }
        if (dto.getItemPromotionDiscount() != null) {
            entity.setItemPromotionDiscount(dto.getItemPromotionDiscount());
        }
        if (dto.getShipPromotionDiscount() != null) {
            entity.setShipPromotionDiscount(dto.getShipPromotionDiscount());
        }
        if (dto.getCurrency() != null) {
            entity.setCurrency(dto.getCurrency());
        }
        if (dto.getBuyerEmail() != null) {
            entity.setBuyerEmail(dto.getBuyerEmail());
        }
        if (dto.getRecipientName() != null) {
            entity.setRecipientName(dto.getRecipientName());
        }
        if (dto.getShipAddressLine1() != null) {
            entity.setShipAddressLine1(dto.getShipAddressLine1());
        }
        if (dto.getShipCity() != null) {
            entity.setShipCity(dto.getShipCity());
        }
        if (dto.getShipState() != null) {
            entity.setShipState(dto.getShipState());
        }
        if (dto.getShipPostalCode() != null) {
            entity.setShipPostalCode(dto.getShipPostalCode());
        }
        if (dto.getShipCountry() != null) {
            entity.setShipCountry(dto.getShipCountry());
        }
        if (dto.getRawData() != null) {
            entity.setRawData(dto.getRawData());
        }
        if (dto.getStoreId() != null) {
            entity.setStoreId(UUID.fromString(dto.getStoreId()));
        }

        entity.setUpdatedAt(LocalDateTime.now());

        orderMapper.updateById(entity);
        log.info("Order updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void deleteOrder(String id) {
        OrderEntity entity = orderMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }
        orderMapper.deleteById(UUID.fromString(id));
        log.info("Order deleted: id={}", id);
    }

    @Override
    public PageResponse<Object> listReturns(String storeId, int page, int pageSize) {
        Page<ReturnEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ReturnEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ReturnEntity> result = returnMapper.selectPage(pageParam, wrapper);
        List<Object> voList = result.getRecords().stream()
                .map(entity -> (Object) entity)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<Object> listRefunds(String storeId, int page, int pageSize) {
        Page<RefundEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<RefundEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<RefundEntity> result = refundMapper.selectPage(pageParam, wrapper);
        List<Object> voList = result.getRecords().stream()
                .map(entity -> (Object) entity)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<Object> listSettlements(String storeId, int page, int pageSize) {
        Page<SettlementEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<SettlementEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<SettlementEntity> result = settlementMapper.selectPage(pageParam, wrapper);
        List<Object> voList = result.getRecords().stream()
                .map(entity -> (Object) entity)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<Object> listBuyerMessages(String storeId, int page, int pageSize) {
        Page<BuyerMessageEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<BuyerMessageEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", storeId);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<BuyerMessageEntity> result = buyerMessageMapper.selectPage(pageParam, wrapper);
        List<Object> voList = result.getRecords().stream()
                .map(entity -> (Object) entity)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    private OrderVo toVo(OrderEntity entity) {
        return OrderVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .orderId(entity.getOrderId())
                .orderItemId(entity.getOrderItemId())
                .purchaseDate(entity.getPurchaseDate() != null ? entity.getPurchaseDate().format(FORMATTER) : null)
                .lastUpdateDate(entity.getLastUpdateDate() != null ? entity.getLastUpdateDate().format(FORMATTER) : null)
                .orderStatus(entity.getOrderStatus())
                .fulfillmentChannel(entity.getFulfillmentChannel())
                .salesChannel(entity.getSalesChannel())
                .marketplaceId(entity.getMarketplaceId())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .productName(entity.getProductName())
                .quantityOrdered(entity.getQuantityOrdered() != null ? entity.getQuantityOrdered() : 0)
                .itemPrice(entity.getItemPrice() != null ? entity.getItemPrice().doubleValue() : 0.0)
                .itemTax(entity.getItemTax() != null ? entity.getItemTax().doubleValue() : 0.0)
                .shippingPrice(entity.getShippingPrice() != null ? entity.getShippingPrice().doubleValue() : 0.0)
                .shippingTax(entity.getShippingTax() != null ? entity.getShippingTax().doubleValue() : 0.0)
                .itemPromotionDiscount(entity.getItemPromotionDiscount() != null ? entity.getItemPromotionDiscount().doubleValue() : 0.0)
                .shipPromotionDiscount(entity.getShipPromotionDiscount() != null ? entity.getShipPromotionDiscount().doubleValue() : 0.0)
                .currency(entity.getCurrency())
                .buyerEmail(entity.getBuyerEmail())
                .recipientName(entity.getRecipientName())
                .shipAddressLine1(entity.getShipAddressLine1())
                .shipCity(entity.getShipCity())
                .shipState(entity.getShipState())
                .shipPostalCode(entity.getShipPostalCode())
                .shipCountry(entity.getShipCountry())
                .rawData(entity.getRawData())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
