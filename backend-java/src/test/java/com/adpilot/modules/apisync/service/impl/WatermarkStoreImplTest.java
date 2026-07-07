package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.entity.SyncWatermarkEntity;
import com.adpilot.modules.apisync.mapper.SyncWatermarkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatermarkStoreImplTest {

    @Mock
    private SyncWatermarkMapper syncWatermarkMapper;

    @InjectMocks
    private WatermarkStoreImpl watermarkStore;

    private final UUID storeId = UUID.randomUUID();
    private final String entityType = "campaign";

    private SyncWatermarkEntity existing(LocalDateTime at) {
        return SyncWatermarkEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .entityType(entityType)
                .watermarkAt(at)
                .build();
    }

    @Test
    void getReturnsEmptyWhenNoWatermarkRow() {
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThat(watermarkStore.get(storeId, entityType)).isEmpty();
    }

    @Test
    void getReturnsEmptyWhenWatermarkAtNull() {
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing(null));

        assertThat(watermarkStore.get(storeId, entityType)).isEmpty();
    }

    @Test
    void getReturnsStoredWatermarkAsUtcInstant() {
        LocalDateTime stored = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing(stored));

        Optional<Instant> result = watermarkStore.get(storeId, entityType);

        assertThat(result).contains(stored.toInstant(ZoneOffset.UTC));
    }

    @Test
    void advanceInsertsWhenNoWatermarkExists() {
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        Instant newWatermark = Instant.parse("2024-05-01T00:00:00Z");

        watermarkStore.advance(storeId, entityType, newWatermark);

        ArgumentCaptor<SyncWatermarkEntity> captor = ArgumentCaptor.forClass(SyncWatermarkEntity.class);
        verify(syncWatermarkMapper).insert(captor.capture());
        SyncWatermarkEntity inserted = captor.getValue();
        assertThat(inserted.getStoreId()).isEqualTo(storeId);
        assertThat(inserted.getEntityType()).isEqualTo(entityType);
        assertThat(inserted.getWatermarkAt()).isEqualTo(LocalDateTime.ofInstant(newWatermark, ZoneOffset.UTC));
        verify(syncWatermarkMapper, never()).updateById(any());
    }

    @Test
    void advanceUpdatesWhenNewWatermarkIsStrictlyGreater() {
        LocalDateTime current = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        SyncWatermarkEntity entity = existing(current);
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
        Instant newWatermark = Instant.parse("2024-02-01T00:00:00Z");

        watermarkStore.advance(storeId, entityType, newWatermark);

        assertThat(entity.getWatermarkAt()).isEqualTo(LocalDateTime.ofInstant(newWatermark, ZoneOffset.UTC));
        verify(syncWatermarkMapper).updateById(entity);
    }

    @Test
    void advanceDoesNotRegressForOlderWatermark() {
        LocalDateTime current = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        SyncWatermarkEntity entity = existing(current);
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
        Instant older = Instant.parse("2024-01-01T00:00:00Z");

        watermarkStore.advance(storeId, entityType, older);

        assertThat(entity.getWatermarkAt()).isEqualTo(current);
        verify(syncWatermarkMapper, never()).updateById(any());
    }

    @Test
    void advanceIsNoOpForEqualWatermark() {
        LocalDateTime current = LocalDateTime.of(2024, 6, 1, 0, 0, 0);
        SyncWatermarkEntity entity = existing(current);
        when(syncWatermarkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);
        Instant equal = current.toInstant(ZoneOffset.UTC);

        watermarkStore.advance(storeId, entityType, equal);

        assertThat(entity.getWatermarkAt()).isEqualTo(current);
        verify(syncWatermarkMapper, never()).updateById(any());
    }

    @Test
    void advanceIsNoOpForNullWatermark() {
        watermarkStore.advance(storeId, entityType, null);

        verify(syncWatermarkMapper, never()).insert(any());
        verify(syncWatermarkMapper, never()).updateById(any());
    }
}
