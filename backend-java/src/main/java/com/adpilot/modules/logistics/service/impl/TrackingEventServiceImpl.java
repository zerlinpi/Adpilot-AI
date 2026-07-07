package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.TrackingEventDto;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.TrackingEventEntity;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.mapper.TrackingEventMapper;
import com.adpilot.modules.logistics.service.TrackingEventService;
import com.adpilot.modules.logistics.vo.TrackingEventVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of {@link TrackingEventService} backing Requirement 8.
 *
 * <p>Persists up to 1,000 tracking events per shipment, validates the
 * timestamp and description, allows an unset leg reference, and lists events
 * newest-first with a most-recently-recorded tiebreaker for equal
 * timestamps.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackingEventServiceImpl implements TrackingEventService {

    private final TrackingEventMapper trackingEventMapper;
    private final ShipmentMapper shipmentMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Maximum number of tracking events allowed per shipment (Req 8.1). */
    private static final int MAX_EVENTS_PER_SHIPMENT = 1000;

    /** Maximum description length in characters (Req 8.1, 8.2). */
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    @Override
    @Transactional
    public TrackingEventVo add(String shipmentId, TrackingEventDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        // Req 8.6: shipment must exist before any tracking events are recorded.
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        if (dto == null) {
            throw new BusinessException("TRACKING_EVENT_INVALID", "Tracking event payload is required");
        }

        // Req 8.2: timestamp is required.
        if (dto.getEventTime() == null) {
            throw new BusinessException("TRACKING_EVENT_INVALID",
                    "Field 'eventTime': event timestamp is required");
        }

        // Req 8.2: description must be present and 1–500 characters.
        String description = dto.getDescription();
        if (description == null || description.trim().isEmpty()) {
            throw new BusinessException("TRACKING_EVENT_INVALID",
                    "Field 'description': description is required");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException("TRACKING_EVENT_INVALID",
                    "Field 'description': description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        // Req 8.3: leg reference is optional; parse only when provided.
        UUID legUuid = parseOptionalLegId(dto.getLegId());

        // Req 8.1: enforce the 1,000-event ceiling, leaving existing events unchanged.
        long existingCount = countEvents(shipmentUuid);
        if (existingCount >= MAX_EVENTS_PER_SHIPMENT) {
            throw new BusinessException("TRACKING_EVENT_LIMIT_EXCEEDED",
                    "Shipment already has the maximum of " + MAX_EVENTS_PER_SHIPMENT + " tracking events");
        }

        TrackingEventEntity entity = TrackingEventEntity.builder()
                .shipmentId(shipmentUuid)
                .legId(legUuid)
                .eventTime(dto.getEventTime())
                // Req 8.5: recorded_at is the tiebreaker for equal event timestamps.
                .recordedAt(LocalDateTime.now())
                .description(description)
                .build();

        trackingEventMapper.insert(entity);
        log.info("Tracking event recorded: id={}, shipmentId={}", entity.getId(), shipmentUuid);

        TrackingEventEntity saved = trackingEventMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    @Override
    public List<TrackingEventVo> list(String shipmentId) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        LambdaQueryWrapper<TrackingEventEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrackingEventEntity::getShipmentId, shipmentUuid)
                // Req 8.4: most recent timestamp first.
                .orderByDesc(TrackingEventEntity::getEventTime)
                // Req 8.5: tiebreaker — most recently recorded first for equal timestamps.
                .orderByDesc(TrackingEventEntity::getRecordedAt);

        return trackingEventMapper.selectList(wrapper).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    private long countEvents(UUID shipmentUuid) {
        LambdaQueryWrapper<TrackingEventEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TrackingEventEntity::getShipmentId, shipmentUuid);
        return trackingEventMapper.selectCount(wrapper);
    }

    private UUID parseShipmentId(String shipmentId) {
        if (shipmentId == null || shipmentId.trim().isEmpty()) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment id is required");
        }
        try {
            return UUID.fromString(shipmentId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }
    }

    private UUID parseOptionalLegId(String legId) {
        if (legId == null || legId.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(legId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("TRACKING_EVENT_INVALID",
                    "Field 'legId': leg reference is not a valid identifier");
        }
    }

    private TrackingEventVo toVo(TrackingEventEntity entity) {
        return TrackingEventVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .legId(entity.getLegId() != null ? entity.getLegId().toString() : null)
                .eventTime(entity.getEventTime() != null ? entity.getEventTime().format(DATETIME_FORMATTER) : null)
                .recordedAt(entity.getRecordedAt() != null ? entity.getRecordedAt().format(DATETIME_FORMATTER) : null)
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
