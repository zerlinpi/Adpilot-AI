package com.adpilot.modules.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("orders")
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "order_id", nullable = false, length = 255)
    private String orderId;

    @Column(name = "order_item_id", length = 255)
    private String orderItemId;

    @Column(name = "purchase_date")
    private LocalDateTime purchaseDate;

    @Column(name = "last_update_date")
    private LocalDateTime lastUpdateDate;

    @Column(name = "order_status", length = 50)
    private String orderStatus;

    @Column(name = "fulfillment_channel", length = 50)
    private String fulfillmentChannel;

    @Column(name = "sales_channel", length = 50)
    private String salesChannel;

    @Column(name = "marketplace_id", length = 50)
    private String marketplaceId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "product_name", length = 500)
    private String productName;

    @Column(name = "quantity_ordered")
    @Builder.Default
    private Integer quantityOrdered = 0;

    @Column(name = "item_price", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal itemPrice = BigDecimal.ZERO;

    @Column(name = "item_tax", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal itemTax = BigDecimal.ZERO;

    @Column(name = "shipping_price", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal shippingPrice = BigDecimal.ZERO;

    @Column(name = "shipping_tax", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal shippingTax = BigDecimal.ZERO;

    @Column(name = "item_promotion_discount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal itemPromotionDiscount = BigDecimal.ZERO;

    @Column(name = "ship_promotion_discount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal shipPromotionDiscount = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "buyer_email", length = 255)
    private String buyerEmail;

    @Column(name = "recipient_name", length = 255)
    private String recipientName;

    @Column(name = "ship_address_line1", length = 500)
    private String shipAddressLine1;

    @Column(name = "ship_city", length = 255)
    private String shipCity;

    @Column(name = "ship_state", length = 100)
    private String shipState;

    @Column(name = "ship_postal_code", length = 50)
    private String shipPostalCode;

    @Column(name = "ship_country", length = 50)
    private String shipCountry;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
