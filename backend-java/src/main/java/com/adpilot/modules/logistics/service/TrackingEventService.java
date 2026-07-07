package com.adpilot.modules.logistics.service;

import com.adpilot.modules.logistics.dto.TrackingEventDto;
import com.adpilot.modules.logistics.vo.TrackingEventVo;

import java.util.List;

/**
 * Service for managing a Shipment's tracking trajectory (轨迹).
 *
 * <p>Backs Requirement 8: up to 1,000 {@code Tracking_Event} entries per
 * shipment, each with a required timestamp, a 1–500 character description, and
 * an optional reference to a {@code Shipment_Leg}.</p>
 */
public interface TrackingEventService {

    /**
     * Record a new tracking event for a shipment.
     *
     * <p>Enforces a maximum of 1,000 events per shipment, requires a timestamp,
     * a description of 1–500 characters, and allows the leg reference to be
     * unset (null). On any validation failure a field-level
     * {@link com.adpilot.common.exception.BusinessException} is thrown and the
     * shipment's existing events are left unchanged (Req 8.1, 8.2, 8.3).</p>
     *
     * @param shipmentId the owning shipment identifier
     * @param dto        the tracking event request
     * @return the persisted tracking event
     */
    TrackingEventVo add(String shipmentId, TrackingEventDto dto);

    /**
     * List a shipment's tracking events ordered most-recent first.
     *
     * <p>Events are ordered by {@code eventTime} descending, with
     * {@code recordedAt} descending as the tiebreaker for equal timestamps so
     * that the most-recently-recorded entry is listed first (Req 8.4, 8.5).</p>
     *
     * @param shipmentId the owning shipment identifier
     * @return the ordered tracking events (empty list when none exist)
     */
    List<TrackingEventVo> list(String shipmentId);
}
