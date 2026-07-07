package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.AdPlacementLockStrategyEntity;
import com.adpilot.modules.advertising.entity.AdPlacementLockTaskEntity;
import com.adpilot.modules.advertising.entity.BidChangeEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.AdPlacementLockStrategyMapper;
import com.adpilot.modules.advertising.mapper.AdPlacementLockTaskMapper;
import com.adpilot.modules.advertising.mapper.BidChangeMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlacementLockEvaluator} (Req 26.3): enforcement clamps
 * linked keyword bids into the strategy's {@code [bidMin, bidMax]} range using the
 * shared {@link HostingBidOptimizer#clampToRange} clamp, upserts per-keyword tasks,
 * writes audit bid-changes only on actual change, and isolates per-strategy failures.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlacementLockEvaluatorTest {

    @Mock private AdPlacementLockStrategyMapper strategyMapper;
    @Mock private AdPlacementLockTaskMapper taskMapper;
    @Mock private KeywordMapper keywordMapper;
    @Mock private BidChangeMapper bidChangeMapper;

    private PlacementLockEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PlacementLockEvaluator(strategyMapper, taskMapper, keywordMapper, bidChangeMapper);
    }

    private AdPlacementLockStrategyEntity strategy(String min, String max) {
        return AdPlacementLockStrategyEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .targetPlacement("top_of_search_1_1")
                .bidMin(new BigDecimal(min))
                .bidMax(new BigDecimal(max))
                .status("active")
                .build();
    }

    private KeywordEntity keyword(String bid) {
        return KeywordEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .adGroupId(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .keywordText("kw")
                .status("enabled")
                .bid(new BigDecimal(bid))
                .build();
    }

    @Test
    void clampsBidBelowRangeUpToMin() {
        AdPlacementLockStrategyEntity s = strategy("0.50", "1.50");
        KeywordEntity kw = keyword("0.20"); // below min -> clamp up to 0.50
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));
        when(taskMapper.selectOne(any())).thenReturn(null);

        int changed = evaluator.enforceStrategy(s);

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<KeywordEntity> kwCaptor = ArgumentCaptor.forClass(KeywordEntity.class);
        verify(keywordMapper).updateById(kwCaptor.capture());
        assertThat(kwCaptor.getValue().getBid()).isEqualByComparingTo("0.50");
        verify(bidChangeMapper).insert(any(BidChangeEntity.class));
        verify(taskMapper).insert(any(AdPlacementLockTaskEntity.class));
    }

    @Test
    void clampsBidAboveRangeDownToMax() {
        AdPlacementLockStrategyEntity s = strategy("0.50", "1.50");
        KeywordEntity kw = keyword("3.00"); // above max -> clamp down to 1.50
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));
        when(taskMapper.selectOne(any())).thenReturn(null);

        evaluator.enforceStrategy(s);

        ArgumentCaptor<KeywordEntity> kwCaptor = ArgumentCaptor.forClass(KeywordEntity.class);
        verify(keywordMapper).updateById(kwCaptor.capture());
        assertThat(kwCaptor.getValue().getBid()).isEqualByComparingTo("1.50");
    }

    @Test
    void leavesBidInsideRangeUnchanged() {
        AdPlacementLockStrategyEntity s = strategy("0.50", "1.50");
        KeywordEntity kw = keyword("1.00"); // already inside range -> no bid write
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));
        when(taskMapper.selectOne(any())).thenReturn(null);

        int changed = evaluator.enforceStrategy(s);

        assertThat(changed).isZero();
        verify(keywordMapper, never()).updateById(any());
        verify(bidChangeMapper, never()).insert(any());
        // a task row is still upserted to record the run.
        verify(taskMapper).insert(any(AdPlacementLockTaskEntity.class));
    }

    @Test
    void updatesExistingTaskInsteadOfInserting() {
        AdPlacementLockStrategyEntity s = strategy("0.50", "1.50");
        KeywordEntity kw = keyword("1.00");
        when(keywordMapper.selectList(any())).thenReturn(List.of(kw));
        when(taskMapper.selectOne(any())).thenReturn(
                AdPlacementLockTaskEntity.builder().id(UUID.randomUUID()).build());

        evaluator.enforceStrategy(s);

        verify(taskMapper).updateById(any(AdPlacementLockTaskEntity.class));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void isolatesPerStrategyFailures() {
        AdPlacementLockStrategyEntity broken = strategy("0.50", "1.50");
        AdPlacementLockStrategyEntity ok = strategy("0.50", "1.50");
        when(strategyMapper.selectList(any())).thenReturn(List.of(broken, ok));
        when(keywordMapper.selectList(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(List.of(keyword("3.00")));
        when(taskMapper.selectOne(any())).thenReturn(null);

        PlacementLockEvaluator.EnforcementSummary summary = evaluator.runOnce();

        assertThat(summary.getStrategiesProcessed()).isEqualTo(2);
        assertThat(summary.getStrategiesFailed()).isEqualTo(1);
        assertThat(summary.getBidsClamped()).isEqualTo(1);
    }
}
