package com.adpilot.modules.advertising.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adpilot.modules.advertising.entity.OptimizationGoalMigrationExceptionEntity;
import com.adpilot.modules.advertising.mapper.OptimizationGoalColumnMigrationMapper;
import com.adpilot.modules.advertising.mapper.OptimizationGoalMigrationExceptionMapper;
import com.adpilot.modules.advertising.service.OptimizationGoalMigrationService;
import com.adpilot.modules.advertising.support.OptimizationGoalColumn;
import com.adpilot.modules.advertising.support.OptimizationGoalColumnMigrationResult;
import com.adpilot.modules.advertising.support.OptimizationGoalColumnRegistry;
import com.adpilot.modules.advertising.support.OptimizationGoalMigrationOutcome;
import com.adpilot.modules.advertising.support.OptimizationGoalMigrationReport;
import com.adpilot.modules.advertising.support.OptimizationGoalValueRow;
import com.adpilot.modules.advertising.support.OptimizationGoalVocabulary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Optimization_Goal enum migration (Requirement 57.2, 57.3).
 *
 * <p>For each column in the {@link OptimizationGoalColumnRegistry registry}, every non-null value is
 * classified by the pure {@link OptimizationGoalVocabulary#normalize} function. The pure classifier
 * decides the action; this service only performs the resulting persistence:</p>
 * <ul>
 *   <li>a {@link OptimizationGoalMigrationOutcome.Kind#NORMALIZED normalized} value is written back as
 *       its canonical enum,</li>
 *   <li>an {@link OptimizationGoalMigrationOutcome.Kind#UNCHANGED unchanged} value is left as-is, and</li>
 *   <li>a {@link OptimizationGoalMigrationOutcome.Kind#FLAGGED flagged} value is recorded in
 *       {@code optimization_goal_migration_exceptions} for manual review and is <strong>never
 *       auto-mapped</strong> to an arbitrary enum value (Requirement 57.3).</li>
 * </ul>
 *
 * <p>{@link #migrateColumn} is transactional, so a directly invoked single-column migration commits
 * atomically. {@link #migrateAll} iterates the columns sequentially; a column's writes are durable
 * once its statements commit, so a problem in a later column cannot undo earlier columns' progress.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizationGoalMigrationServiceImpl implements OptimizationGoalMigrationService {

    private final OptimizationGoalColumnMigrationMapper migrationMapper;
    private final OptimizationGoalMigrationExceptionMapper exceptionMapper;

    @Override
    public OptimizationGoalMigrationReport migrateAll() {
        List<OptimizationGoalColumnMigrationResult> results = new ArrayList<>();
        for (OptimizationGoalColumn column : OptimizationGoalColumnRegistry.columns()) {
            results.add(migrateColumn(column));
        }
        OptimizationGoalMigrationReport report = new OptimizationGoalMigrationReport(results);
        log.info("Optimization_Goal migration complete: normalized={}, unchanged={}, flagged={}",
                report.totalNormalized(), report.totalUnchanged(), report.totalFlagged());
        return report;
    }

    @Override
    @Transactional
    public OptimizationGoalColumnMigrationResult migrateColumn(OptimizationGoalColumn column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        List<OptimizationGoalValueRow> rows = migrationMapper.readColumn(column.table(), column.column());
        int normalized = 0;
        int unchanged = 0;
        int flagged = 0;

        for (OptimizationGoalValueRow row : rows) {
            OptimizationGoalMigrationOutcome outcome = OptimizationGoalVocabulary.normalize(row.value());
            switch (outcome.kind()) {
                case NORMALIZED:
                    migrationMapper.updateValue(column.table(), column.column(), row.id(), outcome.value());
                    normalized++;
                    break;
                case FLAGGED:
                    recordException(column, row, outcome.reason());
                    flagged++;
                    break;
                case UNCHANGED:
                default:
                    unchanged++;
                    break;
            }
        }

        OptimizationGoalColumnMigrationResult result =
                new OptimizationGoalColumnMigrationResult(column.table(), column.column(),
                        normalized, unchanged, flagged);
        log.info("Optimization_Goal migration {}.{}: normalized={}, unchanged={}, flagged={}",
                column.table(), column.column(), normalized, unchanged, flagged);
        return result;
    }

    private void recordException(OptimizationGoalColumn column, OptimizationGoalValueRow row, String reason) {
        OptimizationGoalMigrationExceptionEntity exception = OptimizationGoalMigrationExceptionEntity.builder()
                .tableName(column.table())
                .columnName(column.column())
                .recordId(parseRecordId(row.id()))
                .originalValue(row.value())
                .reason(reason)
                .build();
        exceptionMapper.insert(exception);
        log.warn("Optimization_Goal migration flagged {}.{} record {} value '{}' for manual review: {}",
                column.table(), column.column(), row.id(), row.value(), reason);
    }

    private static UUID parseRecordId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // The exception table records the record id as a UUID; a non-UUID key is itself a data
            // anomaly. Fall back to a deterministic placeholder so the original value/reason still
            // carry the detail needed for review.
            return new UUID(0L, 0L);
        }
    }
}
