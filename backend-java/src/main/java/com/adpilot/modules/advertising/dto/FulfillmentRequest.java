package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload for {@code POST /api/independent-site/orders/{orderId}/fulfillment}
 * — the independent-site fulfillment / shipment mark write-back (Req 4.1, 4.2).
 *
 * <p>The {@code orderId} is taken from the path; this body carries the store the
 * order belongs to (required so the orchestration service can enforce the
 * Store_Group_Scope and Platform_Family boundaries, Req 4.6) and the optional
 * shipment tracking details to push to the connected Shopify / WooCommerce store
 * via the Independent_Site_Write_Connector.
 *
 * <p>The write is never performed synchronously in the request thread; the
 * orchestration service records an Operation + Outbox entry for asynchronous
 * submission (Req 4.2) and refuses the request when the store has no valid
 * connection or lacks credentials (Req 4.3).
 */
@Data
public class FulfillmentRequest {

    /** Store the order belongs to; required for scope / platform-family checks (Req 4.6). */
    @NotBlank(message = "Store ID is required")
    private String storeId;

    /** Shipment tracking number; optional. */
    private String trackingNumber;

    /** Shipping carrier name (e.g. {@code USPS}, {@code DHL}); optional. */
    private String carrier;

    /** Carrier tracking URL; optional. */
    private String trackingUrl;

    /**
     * Whether the platform should notify the customer of the shipment. Optional;
     * when omitted the platform's default notification behaviour applies.
     */
    private Boolean notifyCustomer;
}
