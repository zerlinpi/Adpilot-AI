package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.FbaFieldsDto;
import com.adpilot.modules.logistics.vo.FbaFieldsVo;

/**
 * Service for managing the core {@code FBA_Shipment_Fields} of a Shipment plus
 * its {@code Shipment_Line_Items}.
 *
 * <p>Implements Requirement 16: a Shipment may record an FBA shipment
 * identifier (1–100 chars), an Amazon shipment status, a destination
 * Fulfillment_Center code (1–50 chars), and 0 or more line items (SKU/MSKU
 * 1–100 chars, ASIN 1–20 chars, quantity 1–1,000,000). On any validation
 * failure the service throws a {@link com.adpilot.common.exception.BusinessException}
 * identifying each invalid field and persists nothing, leaving any previously
 * persisted values unchanged (Req 16.5).</p>
 */
public interface FbaShipmentService {

    /**
     * Persist the FBA fields and line items for the given Shipment.
     *
     * <p>Validates the FBA shipment id, Amazon status, destination FC code, and
     * each line item against the Req 16.1/16.2 bounds. On any invalid or
     * over-length field a {@link com.adpilot.common.exception.BusinessException}
     * is thrown identifying every invalid field, and — because the method is
     * transactional and validation runs before any write — nothing is persisted
     * and prior values remain unchanged (Req 16.5). Existing line items are
     * replaced with the submitted set.</p>
     *
     * @param shipmentId the owning Shipment id
     * @param dto        the submitted FBA fields plus line items
     * @return the saved FBA fields and line items (Req 16.3)
     */
    FbaFieldsVo saveFields(String shipmentId, FbaFieldsDto dto);

    /**
     * Read the FBA fields and line items for the given Shipment.
     *
     * <p>Returns only the fields belonging to a Shipment the requester is
     * authorized to access for the Active_Store (Req 16.6); an unknown Shipment
     * yields a not-found error.</p>
     *
     * @param shipmentId the Shipment id
     * @return the persisted FBA fields and line items
     */
    FbaFieldsVo getFields(String shipmentId);
}
