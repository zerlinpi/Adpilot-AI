package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response view for {@code GET /api/independent-site/connection-state} (Req 4.4, 4.7).
 *
 * <p>Surfaces, per independent-site store, the connection / write status and the
 * platform's write capabilities so the frontend can honestly present what can be
 * done — and explicitly mark unavailable capabilities as "暂不支持" rather than
 * offering an entry point that would silently fail (Req 4.7).
 *
 * <p>{@code connectionState} reuses the Connection_State vocabulary:
 * {@code not_authorized} (no credentials / no valid connection),
 * {@code syncing}, {@code failed}, or {@code connected}. The
 * {@code inventoryWriteSupported} / {@code fulfillmentWriteSupported} flags
 * reflect whether the platform's connector exposes the corresponding write
 * capability; when a capability is unavailable the flag is {@code false}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndependentSiteConnectionStateVo {

    /** The independent-site store this state describes. */
    @JsonProperty("store_id")
    private String storeId;

    /** Platform key: {@code shopify} | {@code woocommerce}. */
    @JsonProperty("platform")
    private String platform;

    /**
     * Connection / write status: {@code not_authorized} | {@code syncing} |
     * {@code failed} | {@code connected} (Req 4.4).
     */
    @JsonProperty("connection_state")
    private String connectionState;

    /** Whether the platform/connector supports inventory write-back (Req 4.7). */
    @JsonProperty("inventory_write_supported")
    private boolean inventoryWriteSupported;

    /** Whether the platform/connector supports fulfillment write-back (Req 4.7). */
    @JsonProperty("fulfillment_write_supported")
    private boolean fulfillmentWriteSupported;

    /** Most recent successful write/sync time; null when there has been none (Req 4.4). */
    @JsonProperty("last_success_at")
    private LocalDateTime lastSuccessAt;
}
