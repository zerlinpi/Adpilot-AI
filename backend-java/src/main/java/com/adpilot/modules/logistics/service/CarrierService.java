package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.CarrierDto;
import com.adpilot.modules.logistics.vo.CarrierVo;

import java.util.List;

/**
 * Service for managing {@code Carrier} (承运商) records as reusable, org-scoped
 * entities referenced by shipment legs.
 *
 * <p>Implements Requirement 6 (carriers as managed entities) and Requirement
 * 17.1 (each Carrier is scoped to the requester's Organization, shared across
 * all Stores of that Organization and not readable by any other
 * Organization).</p>
 *
 * <p>A carrier name must be 1–200 characters and a service type 1–100
 * characters (Req 6.1). On any validation failure a
 * {@link com.adpilot.common.exception.BusinessException} is thrown identifying
 * the invalid field and nothing is persisted (Req 6.2).</p>
 */
public interface CarrierService {

    /**
     * Create a new {@code Carrier} scoped to the requester's Organization.
     *
     * <p>Validates that the carrier name is 1–200 characters and the service
     * type is 1–100 characters. On failure a
     * {@link com.adpilot.common.exception.BusinessException} is thrown
     * identifying the invalid field and nothing is persisted (Req 6.1, 6.2).</p>
     *
     * @param dto the submitted carrier payload
     * @return the saved carrier, including its generated id, owning org id, and
     * all field values
     */
    CarrierVo create(CarrierDto dto);

    /**
     * Update an existing {@code Carrier} that belongs to the requester's
     * Organization.
     *
     * <p>Applies the same name/service-type validation as {@link #create}. A
     * carrier that does not exist within the requester's Organization is
     * treated as not found, and on any validation failure nothing is persisted
     * (Req 6.2, 17.1).</p>
     *
     * @param id  the carrier id
     * @param dto the submitted carrier payload
     * @return the saved carrier
     */
    CarrierVo update(String id, CarrierDto dto);

    /**
     * List all {@code Carrier} records belonging to the requester's
     * Organization (Req 6.4). When no carriers exist for the Organization an
     * empty list is returned (Req 6.5).
     *
     * @return the org-scoped carriers, possibly empty
     */
    List<CarrierVo> list();
}
