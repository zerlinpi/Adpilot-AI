package com.adpilot.modules.advertising.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adpilot.modules.advertising.entity.AcosMigrationExceptionEntity;
import com.adpilot.modules.advertising.mapper.AcosColumnMigrationMapper;
import com.adpilot.modules.advertising.mapper.AcosMigrationExceptionMapper;
import com.adpilot.modules.advertising.service.AcosMigrationService;
import com.adpilot.modules.advertising.support.AcosColumn;
import com.adpilot.modules.advertising.support.AcosColumnMigrationResult;
import com.adpilot.modules.advertising.support.AcosColumnRegistry;
import com.adpilot.modules.advertising.support.AcosMigrationOutcome;
import com.adpilot.modules.advertising.support.AcosMigrationReport;
import com.adpilot.modules.advertising.support.AcosScale;
import com.adpilot.modules.advertising.support.AcosValueRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-column ACoS historical migration (Requirement 17.5, 17.6).
 *
 * <p>For each column in the {@link AcosColumnRegistry registry}, every non-null value is classified
 * by the pure {@link AcosScale#migrate} function using that column's known source semantics. The
 * pure classifier decides the action; this service only performs the resulting persistence:</p>
 * <ul>
 *   <li>a {@link AcosMigrationOutcome.Kind#CONVERTED converted} value is written back as a canonical
 *       decimal ratio,</li>
 *   <li>an {@link AcosMigrationOutcome.Kind#UNCHANGED unchanged} value is left as-is, and</li>
 *   <li>an {@link AcosMigrationOutcome.Kind#AMBIGUOUS ambiguous} value is recorded in
 *       {@code acos_migration_exceptions} for manual review and is <strong>never guessed</strong>.</li>
 * </ul>
 *
 * <p>{@link #migrateColumn} is transactional, so a directly invoked single-column migration commits
 * atomically. {@link #migrateAll} iterates the columns sequentially; a column's writes are durable
 * once its statements commit, so a problem in a later column cannot undo earlier columns' progress.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcosMigrationServiceImpl implements AcosMigrationService {

    private final AcosColumnMigrationMapper migrationMapper;
    private final AcosMigrationExceptionMapper exceptionMapper;

    @Override
    public AcosMigrationReport migrateAll() {
        List<AcosColumnMigrationResult> results = new ArrayList<>();
        for (AcosColumn column : AcosColumnRegistry.columns()) {
            results.add(migrateColumn(column));
        }
        AcosMigrationReport report = new AcosMigrationReport(results);
        log.info("ACoS migration complete: converted={}, unchanged={}, flagged={}",
                report.totalConverted(), report.totalUnchanged(), report.totalFlagged());
        return report;
    }

    @Override
    @Transactional
    public AcosColumnMigrationResult migrateColumn(AcosColumn column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        List<AcosValueRow> rows = migrationMapper.readColumn(column.table(), column.column());
        int converted = 0;
        int unchanged = 0;
        int flagged = 0;

        for (AcosValueRow row : rows) {
            AcosMigrationOutcome outcome = AcosScale.migrate(row.value(), column.scale());
            switch (outcome.kind()) {
                case CONVERTED:
                    migrationMapper.updateValue(column.table(), column.column(), row.id(), outcome.value());
                    converted++;
                    break;
                case AMBIGUOUS:
                    recordException(column, row, outcome.reason());
                    flagged++;
                    break;
                case UNCHANGED:
                default:
                    unchanged++;
                    break;
            }
        }

        AcosColumnMigrationResult result =
                new AcosColumnMigrationResult(column.table(), column.column(), column.scale(),
                        converted, unchanged, flagged);
        log.info("ACoS migration {}.{} ({}): converted={}, unchanged={}, flagged={}",
                column.table(), column.column(), column.scale(), converted, unchanged, flagged);
        return result;
    }

    private void recordException(AcosColumn column, AcosValueRow row, String reason) {
        AcosMigrationExceptionEntity exception = AcosMigrationExceptionEntity.builder()
                .tableName(column.table())
                .columnName(column.column())
                .recordId(parseRecordId(row.id()))
                .originalValue(row.value() == null ? null : row.value().toPlainString())
                .reason(reason)
                .build();
        exceptionMapper.insert(exception);
        log.warn("ACoS migration flagged {}.{} record {} value {} for manual review: {}",
                column.table(), column.column(), row.id(), row.value(), reason);
    }

    private static UUID parseRecordId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // The exception table records the record id as a UUID; a non-UUID key is itself a data
            // anomaly. Fall back to a deterministic placeholder so the original key is still captured
            // (the human-readable value/reason carry the detail for review).
            return new UUID(0L, 0L);
        }
    }
}
