package com.adpilot.modules.importcenter.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.importcenter.entity.ImportJobEntity;
import com.adpilot.modules.importcenter.mapper.ImportJobMapper;
import com.adpilot.modules.importcenter.mapper.ImportRowErrorMapper;
import com.adpilot.modules.importcenter.mapper.RawSearchTermReportMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3 — concurrent double-commit guard for {@link ImportServiceImpl#commitImport}.
 *
 * <p>Two callers can both read a committable status ({@code validated} /
 * {@code validation_failed}) before either sets {@code committed}. The fix
 * atomically claims the job with a conditional
 * {@code UPDATE ... SET status='committing' WHERE id=? AND status IN (...)} and
 * proceeds ONLY when exactly one row was affected. This test proves the loser of
 * that race (and a job that is already committing/committed) is rejected without
 * re-inserting any rows.</p>
 */
class ImportCommitConcurrencyTest {

    @SuppressWarnings("unchecked")
    private static ImportServiceImpl serviceWith(ImportJobMapper importJobMapper,
                                                 RawSearchTermReportMapper rawMapper,
                                                 SearchTermMapper searchTermMapper,
                                                 PerformanceDailyMapper performanceDailyMapper,
                                                 CampaignMapper campaignMapper) {
        return new ImportServiceImpl(
                importJobMapper,
                mock(ImportRowErrorMapper.class),
                rawMapper,
                campaignMapper,
                mock(AdGroupMapper.class),
                searchTermMapper,
                performanceDailyMapper,
                mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.modules.store.service.MarketplaceReferenceService.class),
                mock(AuditLogService.class),
                new ObjectMapper(),
                mock(com.adpilot.common.security.DataScopeService.class));
    }

    @Test
    void secondCommitLosingTheAtomicClaimIsRejectedAndInsertsNothing() {
        UUID jobId = UUID.randomUUID();
        ImportJobMapper importJobMapper = mock(ImportJobMapper.class);
        RawSearchTermReportMapper rawMapper = mock(RawSearchTermReportMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);

        // The job still reads as "validated" (this caller passed the pre-check)...
        ImportJobEntity job = ImportJobEntity.builder()
                .id(jobId)
                .storeId(UUID.randomUUID())
                .reportType("search_term")
                .status("validated")
                .build();
        when(importJobMapper.selectById(jobId)).thenReturn(job);
        // ...but the concurrent winner already flipped the status, so the atomic
        // conditional UPDATE affects 0 rows for this caller.
        when(importJobMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        ImportServiceImpl service = serviceWith(importJobMapper, rawMapper, searchTermMapper,
                performanceDailyMapper, campaignMapper);

        assertThatThrownBy(() -> service.commitImport(jobId.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        // Rejected BEFORE any commit work: no raw rows fetched, nothing inserted,
        // and the job is never force-marked "committed".
        verify(searchTermMapper, never()).insert(any());
        verify(performanceDailyMapper, never()).insert(any());
        verify(importJobMapper, never()).updateById(any(ImportJobEntity.class));
    }

    @Test
    void commitOnAlreadyCommittedJobIsRejectedByPreCheck() {
        UUID jobId = UUID.randomUUID();
        ImportJobMapper importJobMapper = mock(ImportJobMapper.class);
        RawSearchTermReportMapper rawMapper = mock(RawSearchTermReportMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);

        ImportJobEntity job = ImportJobEntity.builder()
                .id(jobId)
                .storeId(UUID.randomUUID())
                .reportType("search_term")
                .status("committed")
                .build();
        when(importJobMapper.selectById(jobId)).thenReturn(job);

        ImportServiceImpl service = serviceWith(importJobMapper, rawMapper, searchTermMapper,
                performanceDailyMapper, mock(CampaignMapper.class));

        assertThatThrownBy(() -> service.commitImport(jobId.toString(), null))
                .isInstanceOf(BusinessException.class);

        // Never even attempts the atomic claim, and inserts nothing.
        verify(importJobMapper, never()).update(any(), any(Wrapper.class));
        verify(searchTermMapper, never()).insert(any());
        verify(performanceDailyMapper, never()).insert(any());
    }

    @Test
    void winningCommitClaimsAtomicallyAndMarksCommitted() {
        UUID jobId = UUID.randomUUID();
        ImportJobMapper importJobMapper = mock(ImportJobMapper.class);
        RawSearchTermReportMapper rawMapper = mock(RawSearchTermReportMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        PerformanceDailyMapper performanceDailyMapper = mock(PerformanceDailyMapper.class);

        ImportJobEntity job = ImportJobEntity.builder()
                .id(jobId)
                .storeId(UUID.randomUUID())
                .reportType("search_term")
                .status("validated")
                .totalRows(0)
                .build();
        when(importJobMapper.selectById(jobId)).thenReturn(job);
        // This caller wins the atomic claim.
        when(importJobMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        // No raw rows -> selectList returns an empty list (Mockito default).

        ImportServiceImpl service = serviceWith(importJobMapper, rawMapper, searchTermMapper,
                performanceDailyMapper, mock(CampaignMapper.class));

        service.commitImport(jobId.toString(), null);

        // Happy path still ends by marking the job committed.
        verify(importJobMapper).updateById(any(ImportJobEntity.class));
        assertThat(job.getStatus()).isEqualTo("committed");
    }
}
