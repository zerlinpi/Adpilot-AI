package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.HandlingCostDto;
import com.adpilot.modules.logistics.vo.HandlingCostVo;

import java.util.List;

/**
 * Service for managing {@code Handling_Cost} (费用) lines on a Shipment.
 *
 * <p>Implements Requirement 18: a Shipment may record first-class handling-cost
 * lines, each with an amount in the range 0.00–999,999,999.99, a 3-character
 * currency code, and a description/category of 1–200 characters. An optional
 * per-component exchange rate and cost date may be supplied as conversion
 * provenance consumed by the cost chain.</p>
 */
public interface HandlingCostService {

    /**
     * Create or update a handling-cost line for a Shipment.
     *
     * <p>When {@code id} is {@code null}/blank a new line is inserted; otherwise
     * the existing line identified by {@code id} (and belonging to the given
     * shipment) is updated in place. Validates the amount range
     * (0.00–999,999,999.99), that the currency code is exactly 3 characters, and
     * that the description/category is 1–200 characters (Req 18.1). On any
     * validation failure a {@link com.adpilot.common.exception.BusinessException}
     * is thrown identifying each invalid field and, because the method is
     * transactional and validation runs before any write, nothing is persisted —
     * leaving previously persisted lines unchanged (Req 18.4).</p>
     *
     * @param shipmentId the owning Shipment id
     * @param id         the existing handling-cost line id to update, or
     *                   {@code null}/blank to create a new line
     * @param dto        the handling-cost payload
     * @return the saved handling-cost line, including its amount, currency, and
     * description (Req 18.2, 18.3)
     */
    HandlingCostVo upsert(String shipmentId, String id, HandlingCostDto dto);

    /**
     * Delete a handling-cost line by its id. Deleting a line that does not exist
     * is a no-op.
     *
     * @param id the handling-cost line id
     */
    void delete(String id);

    /**
     * List a Shipment's handling-cost lines, each with its id, amount, currency,
     * and description (Req 18.3).
     *
     * @param shipmentId the owning Shipment id
     * @return the shipment's handling-cost lines
     */
    List<HandlingCostVo> list(String shipmentId);
}
