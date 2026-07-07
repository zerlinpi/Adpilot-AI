package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.ShipmentLegDto;
import com.adpilot.modules.logistics.vo.ShipmentLegVo;

import java.util.List;

/**
 * Service for managing a {@code Shipment}'s ordered multi-leg transport path
 * (头程/尾程 and intermediate legs).
 *
 * <p>Implements Requirement 4 (multi-leg shipment transport path): a shipment
 * may record 1–20 legs, each with a leg type, sequence number, assigned
 * carrier, departure/arrival dates, and a leg cost in the range
 * 0.00–999,999,999.99.</p>
 */
public interface ShipmentLegService {

    /**
     * Create or update a shipment leg. The leg is identified within a shipment
     * by its sequence number: when a leg with the same {@code (shipmentId,
     * sequenceNo)} already exists it is updated in place, otherwise a new leg is
     * inserted.
     *
     * <p>Validates required fields (leg type, sequence number, carrier,
     * departure date, arrival date, leg cost), leg cost range, that the arrival
     * date is on or after the departure date, that the referenced carrier
     * exists, and that the shipment does not exceed 20 legs. On any validation
     * failure a {@link com.adpilot.common.exception.BusinessException} is thrown
     * and the transaction is rolled back, leaving previously persisted leg data
     * unchanged.</p>
     *
     * @param shipmentId the owning shipment id
     * @param dto        the leg payload
     * @return the persisted leg
     */
    ShipmentLegVo upsertLeg(String shipmentId, ShipmentLegDto dto);

    /**
     * List a shipment's legs ordered by sequence number (non-decreasing).
     *
     * @param shipmentId the owning shipment id
     * @return the shipment's legs ordered by sequence number
     */
    List<ShipmentLegVo> listLegs(String shipmentId);
}
