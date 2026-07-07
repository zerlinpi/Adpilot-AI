package com.adpilot.modules.advertising.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adpilot.modules.advertising.entity.ObjectStatusMigrationExceptionEntity;
import com.adpilot.modules.advertising.mapper.ObjectStatusColumnMigrationMapper;
import com.adpilot.modules.advertising.mapper.ObjectStatusMigrationExceptionMapper;
import com.adpilot.modules.advertising.service.ObjectStatusMigrationService;
import com.adpilot.modules.advertising.support.ObjectStatusColumn;
import com.adpilot.modules.advertising.support.ObjectStatusColumnMigrationResult;
import com.adpilot.modules.advertising.support.ObjectStatusColumnRegistry;
import com.adpilot.modules.advertising.support.ObjectStatusMigrationOutcome;
import com.adpilot.modules.advertising.support.ObjectStatusMigrationReport;
import com.adpilot.modules.advertising.support.ObjectStatusValueRow;
import com.adpilot.modules.advertising.support.ObjectStatusVocabulary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Object_Status vocabulary migration (Requirement 16.5, 16.6, 16.7).
 *
 * <p>For each column in the {@link ObjectStatusColumnRegistry registry}, every non-null value is
 * classified by the pure {@link ObjectStatusVocabulary#normalize} function. The pure classifier
 * decides the action; this service only performs the resulting persistence:</p>
 * <ul>
 *   <li>a {@link ObjectStatusMigrationOutcome.Kind#NORMALIZED normalized} value is written back as its
 *       canonical form,</li>
 *   <li>an {@link ObjectStatusMigrationOutcome.Kind#UNCHANGED unchanged} value is left as-is, and</li>
 *   <li>a {@link ObjectStatusMigrationOutcome.Kind#FLAGGED flagged} value is recorded in
 *       {@code object_status_migration_exceptions} for manual review and is <strong>never
 *       auto-mapped</strong> to a canonical value (Requirement 16.6).</li>
 * </ul>
 *
 * <p>{@link #migrateColumn} is transactional, so a directly invoked single-column migration commits
 * atomically. {@link #migrateAll} iterates the columns sequentially; a column's writes are durable
 * once its statements commit, so a problem in a later column cannot undo earlier columns' progress.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObjectStatusMigrationServiceImpl implements ObjectStatusMigrationService {

    private final ObjectStatusColumnMigrationMapper migrationMapper;
    private final ObjectStatusMigrationExceptionMapper exceptionMapper;

    @Override
    public ObjectStatusMigrationReport migrateAll() {
        List<ObjectStatusColumnMigrationResult> results = new ArrayList<>();
        for (ObjectStatusColumn column : ObjectStatusColumnRegistry.columns()) {
            results.add(migrateColumn(column));
        }
        ObjectStatusMigrationReport report = new ObjectStatusMigrationReport(results);
        log.info("Object_Status migration complete: normalized={}, unchanged={}, flagged={}",
                report.totalNormalized(), report.totalUnchanged(), report.totalFlagged());
        return report;
    }

    @Override
    @Transactional
    public ObjectStatusColumnMigrationResult migrateColumn(ObjectStatusColumn column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }
        List<ObjectStatusValueRow> rows = migrationMapper.readColumn(column.table(), column.column());
        int normalized = 0;
        int unchanged = 0;
        int flagged = 0;

        for (ObjectStatusValueRow row : rows) {
            ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize(row.value());
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

        ObjectStatusColumnMigrationResult result =
                new ObjectStatusColumnMigrationResult(column.table(), column.column(),
                        normalized, unchanged, flagged);
        log.info("Object_Status migration {}.{}: normalized={}, unchanged={}, flagged={}",
                column.table(), column.column(), normalized, unchanged, flagged);
        return result;
    }

    private void recordException(ObjectStatusColumn column, ObjectStatusValueRow row, String reason) {
        ObjectStatusMigrationExceptionEntity exception = ObjectStatusMigrationExceptionEntity.builder()
                .tableName(column.table())
                .columnName(column.column())
                .recordId(parseRecordId(row.id()))
                .originalValue(row.value())
                .reason(reason)
                .build();
        exceptionMapper.insert(exception);
        log.warn("Object_Status migration flagged {}.{} record {} value '{}' for manual review: {}",
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
