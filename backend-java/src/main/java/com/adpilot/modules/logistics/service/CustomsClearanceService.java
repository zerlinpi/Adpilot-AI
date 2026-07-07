package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.CustomsClearanceDto;
import com.adpilot.modules.logistics.vo.CustomsClearanceVo;

/**
 * Customs Clearance (清关) tracking for a Shipment.
 *
 * <p>Each Shipment has at most one Customs_Clearance record (unique
 * {@code shipment_id}). The service validates and persists clearance status,
 * declaration reference, and duties/taxes, and returns a not-started state when
 * no record exists (Req 7).</p>
 */
public interface CustomsClearanceService {

    /**
     * Create or update (upsert) the Customs_Clearance for a Shipment.
     *
     * <p>Validates that the clearance status is one of not-started, declared,
     * in-review, cleared, or held; the declaration reference is 1–100
     * characters; and the duties/taxes amount is between 0.00 and
     * 999,999,999.99. On any validation failure a {@link
     * com.adpilot.common.exception.BusinessException} is thrown and no change is
     * persisted, leaving any previously persisted record unchanged
     * (Req 7.1, 7.3, 7.4).</p>
     *
     * @param shipmentId the owning Shipment identifier
     * @param dto        the submitted clearance values
     * @return the saved Customs_Clearance (Req 7.2)
     */
    CustomsClearanceVo update(String shipmentId, CustomsClearanceDto dto);

    /**
     * Read the Customs_Clearance for a Shipment, returning a not-started default
     * state when no record exists (Req 7.6).
     *
     * @param shipmentId the owning Shipment identifier
     * @return the persisted record, or a not-started default state
     */
    CustomsClearanceVo get(String shipmentId);
}
