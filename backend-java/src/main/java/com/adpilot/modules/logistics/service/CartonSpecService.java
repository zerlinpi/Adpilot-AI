package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.vo.CartonSpecVo;
import com.adpilot.modules.logistics.vo.CartonTotalsVo;

/**
 * Service for managing {@code Carton_Spec} (箱规) entries on a Shipment.
 *
 * <p>Implements Requirement 5: a Shipment may record between 1 and 100 carton
 * specs with bounded dimensions/weight/units/box-count; totals are computed as
 * the sum of box counts and the sum of (units per box × box count).</p>
 */
public interface CartonSpecService {

    /**
     * Persist a new {@code Carton_Spec} for the given Shipment.
     *
     * <p>Enforces the per-shipment limit of 1–100 carton specs and the field
     * bounds defined in Requirement 5.1. On any validation failure a
     * {@link com.adpilot.common.exception.BusinessException} is thrown
     * identifying each invalid field, and nothing is persisted (Req 5.5).</p>
     *
     * @param shipmentId the owning Shipment id
     * @param dto        the submitted carton spec
     * @return the saved carton spec, including its generated id and all field
     * values (Req 5.2)
     */
    CartonSpecVo addSpec(String shipmentId, CartonSpecDto dto);

    /**
     * Compute the aggregated carton totals for a Shipment: total box count
     * (sum of box counts) and total unit quantity (sum of units-per-box ×
     * box-count). An empty set yields totals of 0 and 0 (Req 5.3, 5.4).
     *
     * @param shipmentId the Shipment id
     * @return the computed totals
     */
    CartonTotalsVo totals(String shipmentId);
}
