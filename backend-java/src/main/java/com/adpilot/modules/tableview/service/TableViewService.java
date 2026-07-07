package com.adpilot.modules.tableview.service;

import com.adpilot.modules.tableview.dto.ColumnConfigDto;
import com.adpilot.modules.tableview.dto.SavedViewDto;
import com.adpilot.modules.tableview.vo.ColumnConfigVo;
import com.adpilot.modules.tableview.vo.SavedViewVo;

import java.util.List;

/**
 * Service for managing per-user, store-independent {@code Saved_View} and
 * {@code Column_Configuration} records.
 *
 * <p>Every operation is scoped to the current authenticated user (resolved via
 * {@link com.adpilot.common.utils.SecurityUtils#getCurrentUserId()}). Saved
 * views and column configs follow the user across Stores and are never readable
 * by another user (Req 2.12, 17.5).</p>
 *
 * <p><b>Saved_View no-Operation rule (advertising-workspace-rework Req 3.9).</b>
 * A Saved_View change (create, update, or delete of a saved view or column
 * configuration) is a personal-preference change, NOT an advertising
 * {@code Operation}. Creating, updating, or deleting a Saved_View MUST NOT
 * create an {@code Operation_Record} (the {@code operations} table) and MUST NOT
 * write an operation-log entry, so that personal view changes never pollute the
 * advertising operation history. This is enforced structurally: this service
 * and its implementation collaborate ONLY with the saved-view and
 * column-configuration mappers and never with the Operation model, the
 * {@code OperationRecordService}/{@code OperationService}, or the
 * authorization {@code AuditService}. The
 * {@code SavedViewNoOperationPropertyTest} guards against a regression that
 * would wire any of those into this path.</p>
 */
public interface TableViewService {

    /**
     * List the current user's saved views for a table key, ordered by name.
     * Returns an empty list when none exist. Never returns another user's
     * records (Req 2.11, 2.12, 17.5).
     *
     * @param tableKey the table identifier
     * @return the user-scoped saved views, possibly empty
     */
    List<SavedViewVo> list(String tableKey);

    /**
     * Save a new saved view for the current user and table key.
     *
     * <p>Validates that the name is 1–100 characters and is not identical to an
     * existing saved view for the same {@code (user, tableKey)}. On an empty,
     * over-length, or duplicate name a
     * {@link com.adpilot.common.exception.BusinessException} is thrown and the
     * existing saved views are left unchanged (Req 2.11, 2.13).</p>
     *
     * @param dto the saved-view payload
     * @return the persisted saved view
     */
    SavedViewVo save(SavedViewDto dto);

    /**
     * Delete a saved view owned by the current user. A view that does not exist
     * for the current user is treated as not found (Req 2.12, 17.5).
     *
     * @param id the saved-view id
     */
    void delete(String id);

    /**
     * Get the current user's column configuration for a table key, or
     * {@code null} when none has been saved (Req 2.12, 17.5).
     *
     * @param tableKey the table identifier
     * @return the user-scoped column configuration, or {@code null}
     */
    ColumnConfigVo getColumns(String tableKey);

    /**
     * Create or update (upsert) the current user's column configuration for a
     * table key, keyed by {@code (user, tableKey)} (Req 2.12).
     *
     * @param dto the column-configuration payload
     * @return the persisted column configuration
     */
    ColumnConfigVo saveColumns(ColumnConfigDto dto);
}
