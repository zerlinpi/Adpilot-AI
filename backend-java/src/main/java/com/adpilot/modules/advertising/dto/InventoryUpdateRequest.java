package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * Payload for {@code POST /api/independent-site/products/{productId}/inventory}
 * — the independent-site inventory update write-back (Req 4.1, 4.2).
 *
 * <p>The {@code productId} is taken from the path; this body carries the store
 * the product belongs to (required so the orchestration service can enforce the
 * Store_Group_Scope and Platform_Family boundaries, Req 4.6) and the new
 * absolute available quantity to push to the connected Shopify / WooCommerce
 * store via the Independent_Site_Write_Connector.
 *
 * <p>The write is never performed synchronously in the request thread; the
 * orchestration service records an Operation + Outbox entry for asynchronous
 * submission (Req 4.2) and refuses the request when the store has no valid
 * connection or lacks credentials (Req 4.3).
 */
@Data
public class InventoryUpdateRequest {

    /** Store the product belongs to; required for scope / platform-family checks (Req 4.6). */
    @NotBlank(message = "Store ID is required")
    private String storeId;

    /**
     * Variant / listing SKU whose inventory is being set. Optional: when omitted
     * the update targets the product's default/only variant.
     */
    private String sku;

    /** New absolute available quantity to set; required and must be zero or greater. */
    @NotNull(message = "Quantity is required")
    @PositiveOrZero(message = "Quantity must be zero or greater")
    private Integer quantity;

    /**
     * Optional platform inventory location id (e.g. a Shopify location). When
     * omitted the platform's default/primary location is used.
     */
    private String locationId;
}
