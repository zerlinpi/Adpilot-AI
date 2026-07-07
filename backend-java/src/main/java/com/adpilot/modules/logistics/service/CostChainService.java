package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.vo.CostChainVo;

/**
 * Service that composes a Shipment's end-to-end Cost_Chain (Req 10).
 *
 * <p>The cost chain is computed (never stored) from the shipment's
 * {@code Shipment_Leg} costs, its {@code Customs_Clearance} duties-and-taxes
 * amount, and its {@code Handling_Cost} lines (Req 10.1, 18.5). It is computed
 * at the shipment level only; SKU-level cost allocation is out of scope
 * (Req 10.7).</p>
 */
public interface CostChainService {

    /**
     * Compute the itemized cost chain and aggregated total landed logistics cost
     * for a shipment.
     *
     * <p>Sums leg costs, the customs duties-and-taxes amount, and handling-cost
     * lines into a {@code totalLandedCost} expressed in the shipment's reporting
     * currency. Components recorded in a currency other than the reporting
     * currency are converted using the per-component exchange rate when present,
     * otherwise the documented store reporting-currency rate effective on the
     * component's cost date; converted amounts are rounded to 2 decimal places
     * half-up and the original amount and currency are retained alongside
     * (Req 10.2, 10.4, 10.5). A component with no recorded amount contributes
     * zero (Req 10.6).</p>
     *
     * @param shipmentId the shipment id
     * @return the itemized cost chain and aggregated total
     * @throws com.adpilot.common.exception.BusinessException with code
     *                                                        {@code SHIPMENT_NOT_FOUND}
     *                                                        when the shipment is
     *                                                        unknown (Req 10.8)
     */
    CostChainVo compute(String shipmentId);
}
