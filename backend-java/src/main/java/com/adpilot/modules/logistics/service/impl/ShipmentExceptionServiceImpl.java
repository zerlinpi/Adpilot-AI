package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.dto.ShipmentExceptionDto;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentExceptionEntity;
import com.adpilot.modules.logistics.mapper.ShipmentExceptionMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.service.ShipmentExceptionService;
import com.adpilot.modules.logistics.vo.ShipmentExceptionVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link ShipmentExceptionService} implementation backing Requirement 9.
 *
 * <p>Validation is performed in the service layer (mirroring the conventions of
 * {@code ShipmentLegServiceImpl}/{@code TrackingEventServiceImpl}): on any
 * invalid field a {@link BusinessException} is thrown with a message
 * identifying the invalid field, and — because the persisting methods are
 * {@link Transactional} and validation runs before any write — nothing is
 * persisted (Req 9.3).</p>
 *
 * <p>Exceptions follow a one-way {@code open -> resolved} transition: a newly
 * raised exception is persisted and returned in the open state (Req 9.2);
 * resolving an open exception records the resolving {@code Audit_Context} actor
 * and timestamp (Req 9.4); re-resolving an already-resolved exception is
 * rejected and leaves the stored record unchanged (Req 9.5). Listing returns
 * only exceptions belonging to shipments of the Active_Store — the stores within
 * the requester's effective data scope (Req 9.7).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentExceptionServiceImpl implements ShipmentExceptionService {

    private final ShipmentExceptionMapper shipmentExceptionMapper;
    private final ShipmentMapper shipmentMapper;
    private final StoreMapper storeMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Permitted exception types (Req 9.1). */
    private static final Set<String> VALID_EXCEPTION_TYPES = Set.of("delay", "damage", "customs-hold");

    /** Description length bounds (Req 9.1). */
    private static final int DESCRIPTION_MIN = 1;
    private static final int DESCRIPTION_MAX = 1000;

    /** Resolution states (Req 9.1). */
    private static final String STATE_OPEN = "open";
    private static final String STATE_RESOLVED = "resolved";

    @Override
    @Transactional
    public ShipmentExceptionVo raise(String shipmentId, ShipmentExceptionDto dto) {
        UUID shipmentUuid = parseShipmentId(shipmentId);

        // Shipment must exist before an exception can be raised against it.
        ShipmentEntity shipment = shipmentMapper.selectById(shipmentUuid);
        if (shipment == null) {
            throw new BusinessException("SHIPMENT_NOT_FOUND", "Shipment not found: " + shipmentId);
        }

        // Field-level validation (Req 9.1, 9.3): collect every invalid field so
        // the error identifies each problem, and throw before any write.
        validate(dto);

        ShipmentExceptionEntity entity = ShipmentExceptionEntity.builder()
                .shipmentId(shipmentUuid)
                .exceptionType(dto.getExceptionType().trim())
                .description(dto.getDescription().trim())
                .resolutionState(STATE_OPEN)
                .build();

        shipmentExceptionMapper.insert(entity);
        log.info("Shipment exception raised: id={}, shipmentId={}, type={}",
                entity.getId(), shipmentUuid, entity.getExceptionType());

        // Re-read so the generated id and timestamps are populated from persistence.
        ShipmentExceptionEntity saved = shipmentExceptionMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    @Override
    @Transactional
    public ShipmentExceptionVo resolve(String exceptionId) {
        UUID exceptionUuid = parseExceptionId(exceptionId);

        ShipmentExceptionEntity existing = shipmentExceptionMapper.selectById(exceptionUuid);
        if (existing == null) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception not found: " + exceptionId);
        }

        // Req 9.5: re-resolving an already-resolved exception is rejected and the
        // stored record is left unchanged.
        if (STATE_RESOLVED.equals(existing.getResolutionState())) {
            throw new BusinessException("EXCEPTION_ALREADY_RESOLVED",
                    "Shipment exception is already resolved: " + exceptionId);
        }

        // Req 9.4: transition open -> resolved, recording the resolving actor and timestamp.
        existing.setResolutionState(STATE_RESOLVED);
        existing.setResolvedBy(currentActorId());
        existing.setResolvedAt(LocalDateTime.now());

        shipmentExceptionMapper.updateById(existing);
        log.info("Shipment exception resolved: id={}, resolvedBy={}", exceptionUuid, existing.getResolvedBy());

        ShipmentExceptionEntity saved = shipmentExceptionMapper.selectById(exceptionUuid);
        return toVo(saved != null ? saved : existing);
    }

    @Override
    public List<ShipmentExceptionVo> listForStore(String storeId) {
        // Req 9.7: only exceptions belonging to shipments of the Active_Store —
        // the stores within the requester's effective data scope.
        List<UUID> storeIds = accessibleStoreIds();
        if (storeIds.isEmpty()) {
            return List.of();
        }
        if (storeId != null && !storeId.isBlank()) {
            UUID requestedStoreId;
            try {
                requestedStoreId = UUID.fromString(storeId);
            } catch (IllegalArgumentException ex) {
                return List.of();
            }
            if (!storeIds.contains(requestedStoreId)) {
                return List.of();
            }
            storeIds = List.of(requestedStoreId);
        }

        LambdaQueryWrapper<ShipmentEntity> shipmentWrapper = new LambdaQueryWrapper<>();
        shipmentWrapper.in(ShipmentEntity::getStoreId, storeIds);
        shipmentWrapper.select(ShipmentEntity::getId);
        List<ShipmentEntity> shipments = shipmentMapper.selectList(shipmentWrapper);
        if (shipments == null || shipments.isEmpty()) {
            return List.of();
        }

        List<UUID> shipmentIds = shipments.stream()
                .map(ShipmentEntity::getId)
                .collect(Collectors.toList());

        LambdaQueryWrapper<ShipmentExceptionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ShipmentExceptionEntity::getShipmentId, shipmentIds);
        wrapper.orderByDesc(ShipmentExceptionEntity::getCreatedAt);

        return shipmentExceptionMapper.selectList(wrapper).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Validate exception type (one of delay/damage/customs-hold) and description
     * (1–1000), collecting every problem so the error identifies each invalid
     * field (Req 9.1, 9.3). Throws before any persistence occurs.
     */
    private void validate(ShipmentExceptionDto dto) {
        if (dto == null) {
            throw new BusinessException("EXCEPTION_INVALID", "Shipment exception payload is required");
        }

        List<String> errors = new ArrayList<>();

        String type = dto.getExceptionType();
        if (type == null || type.trim().isEmpty()) {
            errors.add("exceptionType: exception type is required");
        } else if (!VALID_EXCEPTION_TYPES.contains(type.trim())) {
            errors.add("exceptionType: exception type must be one of delay, damage, customs-hold");
        }

        String description = dto.getDescription();
        if (description == null || description.trim().isEmpty()) {
            errors.add("description: description is required");
        } else {
            int length = description.trim().length();
            if (length < DESCRIPTION_MIN || length > DESCRIPTION_MAX) {
                errors.add("description: description must be between " + DESCRIPTION_MIN
                        + " and " + DESCRIPTION_MAX + " characters");
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessException("EXCEPTION_INVALID", String.join("; ", errors));
        }
    }

    /** Resolve the accessible store ids from the requester's effective data scope. */
    private List<UUID> accessibleStoreIds() {
        CurrentUser user = SecurityUtils.getCurrentUser();
        QueryWrapper<StoreEntity> wrapper = new QueryWrapper<>();
        dataScopeService.applyScope(
                wrapper,
                ScopeTarget.builder().storeIdColumn("id").ownerIdColumn("created_by").build(),
                user);
        List<StoreEntity> stores = storeMapper.selectList(wrapper);
        if (stores == null || stores.isEmpty()) {
            return List.of();
        }
        return stores.stream()
                .map(StoreEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** The resolving actor from the {@code Audit_Context} (Req 9.4). */
    private UUID currentActorId() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("AUTH_001", "No acting user could be determined");
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("AUTH_001", "Acting user id is not a valid identifier: " + userId);
        }
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

    private UUID parseExceptionId(String exceptionId) {
        if (exceptionId == null || exceptionId.trim().isEmpty()) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception id is required");
        }
        try {
            return UUID.fromString(exceptionId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("EXCEPTION_NOT_FOUND", "Shipment exception not found: " + exceptionId);
        }
    }

    private ShipmentExceptionVo toVo(ShipmentExceptionEntity entity) {
        return ShipmentExceptionVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .shipmentId(entity.getShipmentId() != null ? entity.getShipmentId().toString() : null)
                .exceptionType(entity.getExceptionType())
                .description(entity.getDescription())
                .resolutionState(entity.getResolutionState())
                .resolvedBy(entity.getResolvedBy() != null ? entity.getResolvedBy().toString() : null)
                .resolvedAt(entity.getResolvedAt() != null ? entity.getResolvedAt().format(DATETIME_FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
