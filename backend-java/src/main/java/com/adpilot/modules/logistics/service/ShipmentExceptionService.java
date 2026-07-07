package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.ShipmentExceptionDto;
import com.adpilot.modules.logistics.vo.ShipmentExceptionVo;

import java.util.List;

/**
 * Service for raising and resolving {@code Shipment_Exception} (异常) records
 * against a {@code Shipment}.
 *
 * <p>Implements Requirement 9 (shipment exceptions): an exception has an
 * exception type (one of delay, damage, customs-hold), a description of 1–1000
 * characters, and follows a one-way {@code open -> resolved} resolution
 * transition. Newly raised exceptions are returned in the open state; resolving
 * an exception records the resolving {@code Audit_Context} actor, and
 * re-resolving an already-resolved exception is rejected without altering the
 * stored record.</p>
 */
public interface ShipmentExceptionService {

    /**
     * Raise a new {@code Shipment_Exception} against a shipment in the open
     * state.
     *
     * <p>Validates that the exception type is one of delay/damage/customs-hold
     * and the description is 1–1000 characters. On any validation failure a
     * {@link com.adpilot.common.exception.BusinessException} is thrown
     * identifying the invalid field and — because the method is transactional
     * and validation runs before any write — nothing is persisted (Req 9.1,
     * 9.3).</p>
     *
     * @param shipmentId the owning shipment id
     * @param dto        the exception payload
     * @return the persisted exception in the open state (Req 9.2)
     */
    ShipmentExceptionVo raise(String shipmentId, ShipmentExceptionDto dto);

    /**
     * Resolve an open {@code Shipment_Exception}, transitioning it from
     * {@code open} to {@code resolved} and recording the resolving
     * {@code Audit_Context} actor and resolution timestamp (Req 9.4).
     *
     * <p>If the exception is already in the resolved state the request is
     * rejected with a {@link com.adpilot.common.exception.BusinessException}
     * and the stored record is left unchanged (Req 9.5).</p>
     *
     * @param exceptionId the exception id
     * @return the updated, resolved exception
     */
    ShipmentExceptionVo resolve(String exceptionId);

    /**
     * List the {@code Shipment_Exception} entries belonging to shipments of the
     * Active_Store — i.e. the stores within the requester's effective data scope
     * (Req 9.7).
     *
     * @return the exceptions visible to the requester, or an empty list when
     *         none exist
     */
    List<ShipmentExceptionVo> listForStore(String storeId);
}
