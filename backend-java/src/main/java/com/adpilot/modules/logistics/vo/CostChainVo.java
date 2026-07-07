package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response VO for a Shipment's itemized Cost_Chain (Req 10).
 *
 * <p>Lists each cost component (shipment-leg costs, the customs
 * duties-and-taxes amount, and handling-cost lines) and the aggregated
 * {@link #totalLandedCost}, all expressed in the shipment's
 * {@link #reportingCurrency}. Components recorded in another currency carry the
 * converted amount plus the retained original amount, original currency, and
 * applied rate (Req 10.4, 10.5). Components with no recorded amount contribute
 * zero (Req 10.6). The cost chain is computed at the shipment level only — no
 * SKU-level allocation (Req 10.7).</p>
 */
@Data
@Builder
public class CostChainVo {

    private String shipmentId;
    private String reportingCurrency;
    private List<LegCostComponent> legCosts;
    private BigDecimal customsDutiesTaxes;
    private List<HandlingCostComponent> handlingCosts;

    /** Arithmetic sum of all components in the reporting currency, 2 dp half-up. */
    private BigDecimal totalLandedCost;

    /**
     * A shipment-leg cost component. {@code amount} is expressed in the
     * reporting currency. The {@code originalAmount}, {@code originalCurrency},
     * and {@code rate} are populated only when the component was converted from
     * another currency, and are {@code null} otherwise.
     */
    public record LegCostComponent(
            String legId,
            BigDecimal amount,
            BigDecimal originalAmount,
            String originalCurrency,
            BigDecimal rate) {
    }

    /**
     * A handling-cost component. {@code amount} is expressed in the reporting
     * currency. The {@code originalAmount}, {@code originalCurrency}, and
     * {@code rate} are populated only when the component was converted from
     * another currency, and are {@code null} otherwise.
     */
    public record HandlingCostComponent(
            String id,
            BigDecimal amount,
            BigDecimal originalAmount,
            String originalCurrency,
            BigDecimal rate) {
    }
}
