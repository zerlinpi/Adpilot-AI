package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorEntity;
import com.adpilot.modules.advertising.hosting.ReportSyncErrorMapper;
import com.adpilot.modules.advertising.hosting.ReportSyncRunEntity;
import com.adpilot.modules.advertising.hosting.ReportSyncRunMapper;
import com.adpilot.modules.advertising.service.ProductAdSyncStatusService;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.advertising.vo.ProductAdSyncStatusVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link ProductAdSyncStatusService} that reads report-sync observability
 * from {@code report_sync_runs} / {@code report_sync_errors} (Req 2.5, 2.6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAdSyncStatusServiceImpl implements ProductAdSyncStatusService {

    private static final String STATUS_FAILED = "failed";
    private static final String GENERIC_FAILURE = "报表同步失败，但未记录详细错误信息。";

    /**
     * Store-scope target for sync-run reads. The {@code report_sync_runs} rows expose
     * the owning store via {@code store_id}, so the shared data-scope layer can inject
     * a Store_Group_Scope predicate confining non-super-admins to their stores (Req 2.7).
     */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    private final ReportSyncRunMapper reportSyncRunMapper;
    private final ReportSyncErrorMapper reportSyncErrorMapper;
    private final DataScopeService dataScopeService;

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /**
     * Orders runs newest-first using {@code startedAt}, falling back to
     * {@code createdAt} so runs that never started still sort sensibly.
     */
    private static LocalDateTime runOrderKey(ReportSyncRunEntity run) {
        if (run.getStartedAt() != null) {
            return run.getStartedAt();
        }
        return run.getCreatedAt();
    }

    @Override
    public List<ProductAdSyncStatusVo> getSyncStatus(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return Collections.emptyList();
        }

        UUID storeUuid = UUID.fromString(storeId);

        // Sync status is keyed by Store; reject when the caller does not own the
        // requested Store without disclosing its contents (Req 2.7).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(StoreScopeRef.of(storeUuid), user);
        }

        QueryWrapper<ReportSyncRunEntity> query = new QueryWrapper<>();
        query.eq("store_id", storeUuid);
        // Defense-in-depth: inject the account's Store_Group_Scope predicate so a
        // non-super-admin only ever sees sync runs for stores within their scope, in
        // addition to the single-store assertCanRead guard above (Req 2.7).
        if (user != null) {
            dataScopeService.applyScope(query, STORE_SCOPE, user);
        }
        List<ReportSyncRunEntity> runs = reportSyncRunMapper.selectList(query);
        if (runs == null || runs.isEmpty()) {
            return Collections.emptyList();
        }

        // Group runs by report type, preserving first-seen ordering for stable output.
        Map<String, List<ReportSyncRunEntity>> byType = new LinkedHashMap<>();
        for (ReportSyncRunEntity run : runs) {
            byType.computeIfAbsent(run.getReportType(), k -> new ArrayList<>()).add(run);
        }

        Comparator<ReportSyncRunEntity> newestFirst = Comparator
                .comparing(ProductAdSyncStatusServiceImpl::runOrderKey,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();

        List<ProductAdSyncStatusVo> result = new ArrayList<>(byType.size());
        for (Map.Entry<String, List<ReportSyncRunEntity>> entry : byType.entrySet()) {
            List<ReportSyncRunEntity> typeRuns = entry.getValue();
            typeRuns.sort(newestFirst);

            ReportSyncRunEntity latest = typeRuns.get(0);
            String reportStatus = latest.getReportStatus();

            // Most recent successful completion across all runs of this type.
            LocalDateTime lastSuccessAt = typeRuns.stream()
                    .filter(r -> "completed".equalsIgnoreCase(r.getReportStatus()))
                    .map(ReportSyncRunEntity::getCompletedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);

            // A failed run must always surface a readable reason — never a blank or
            // fabricated success (Req 2.6).
            String lastError = null;
            if (STATUS_FAILED.equalsIgnoreCase(reportStatus)) {
                lastError = resolveFailureReason(latest);
            }

            result.add(new ProductAdSyncStatusVo(
                    storeId,
                    entry.getKey(),
                    reportStatus,
                    lastSuccessAt,
                    lastError));
        }

        return result;
    }

    /**
     * Resolve a readable failure reason for a failed run: prefer the run's own
     * error, then the latest recorded per-attempt error, finally a generic
     * non-empty fallback so a failed sync is never reported without a reason.
     */
    private String resolveFailureReason(ReportSyncRunEntity run) {
        if (run.getError() != null && !run.getError().isBlank()) {
            return run.getError();
        }

        LambdaQueryWrapper<ReportSyncErrorEntity> errorQuery = new LambdaQueryWrapper<>();
        errorQuery.eq(ReportSyncErrorEntity::getRunId, run.getId())
                .orderByDesc(ReportSyncErrorEntity::getAttempt)
                .orderByDesc(ReportSyncErrorEntity::getCreatedAt)
                .last("LIMIT 1");
        List<ReportSyncErrorEntity> errors = reportSyncErrorMapper.selectList(errorQuery);
        if (!errors.isEmpty()) {
            String message = errors.get(0).getError();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }

        return GENERIC_FAILURE;
    }
}
