package com.adpilot.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("inventory_snapshots")
@Table(name = "inventory_snapshots")
public class InventorySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "product_id", columnDefinition = "char(36)")
    private UUID productId;

    @Column(name = "marketplace_id", columnDefinition = "char(36)")
    private UUID marketplaceId;

    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "available_inventory")
    private Integer availableInventory;

    @Column(name = "reserved_inventory")
    private Integer reservedInventory;

    @Column(name = "inbound_inventory")
    private Integer inboundInventory;

    @Column(name = "transfer_inventory")
    private Integer transferInventory;

    @Column(name = "unsellable_inventory")
    private Integer unsellableInventory;

    @Column(name = "total_inventory")
    private Integer totalInventory;

    @Column(name = "inventory_value")
    private BigDecimal inventoryValue;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
