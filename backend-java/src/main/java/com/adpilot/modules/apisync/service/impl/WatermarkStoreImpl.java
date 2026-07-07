package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.entity.SyncWatermarkEntity;
import com.adpilot.modules.apisync.mapper.SyncWatermarkMapper;
import com.adpilot.modules.apisync.service.WatermarkStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WatermarkStore} backed by the {@code sync_watermarks} table via
 * {@link SyncWatermarkMapper}.
 *
 * <p>Watermarks are persisted as UTC {@link LocalDateTime} values and exposed
 * as {@link Instant} to callers. Requirements 1.1.6-1.1.8.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatermarkStoreImpl implements WatermarkStore {

    private final SyncWatermarkMapper syncWatermarkMapper;

    @Override
    public Optional<Instant> get(UUID storeId, String entityType) {
        SyncWatermarkEntity entity = findOne(storeId, entityType);
        if (entity == null || entity.getWatermarkAt() == null) {
            // Req 1.1.7: no prior watermark -> caller performs a full pull.
            return Optional.empty();
        }
        return Optional.of(toInstant(entity.getWatermarkAt()));
    }

    @Override
    @Transactional
    public void advance(UUID storeId, String entityType, Instant newWatermark) {
        if (newWatermark == null) {
            return;
        }
        LocalDateTime candidate = toLocalDateTime(newWatermark);
        SyncWatermarkEntity entity = findOne(storeId, entityType);

        if (entity == null) {
            SyncWatermarkEntity created = SyncWatermarkEntity.builder()
                    .storeId(storeId)
                    .entityType(entityType)
                    .watermarkAt(candidate)
                    .build();
            syncWatermarkMapper.insert(created);
            return;
        }

        // Req 1.1.8: advance only; never regress the watermark.
        LocalDateTime current = entity.getWatermarkAt();
        if (current != null && !candidate.isAfter(current)) {
            return;
        }
        entity.setWatermarkAt(candidate);
        syncWatermarkMapper.updateById(entity);
    }

    private SyncWatermarkEntity findOne(UUID storeId, String entityType) {
        LambdaQueryWrapper<SyncWatermarkEntity> wrapper = new LambdaQueryWrapper<SyncWatermarkEntity>()
                .eq(SyncWatermarkEntity::getStoreId, storeId)
                .eq(SyncWatermarkEntity::getEntityType, entityType)
                .last("limit 1");
        return syncWatermarkMapper.selectOne(wrapper);
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
