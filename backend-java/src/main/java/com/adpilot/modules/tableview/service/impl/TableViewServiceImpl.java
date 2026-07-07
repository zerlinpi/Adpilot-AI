package com.adpilot.modules.tableview.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.tableview.dto.ColumnConfigDto;
import com.adpilot.modules.tableview.dto.SavedViewDto;
import com.adpilot.modules.tableview.entity.ColumnConfigEntity;
import com.adpilot.modules.tableview.entity.SavedViewEntity;
import com.adpilot.modules.tableview.mapper.ColumnConfigMapper;
import com.adpilot.modules.tableview.mapper.SavedViewMapper;
import com.adpilot.modules.tableview.service.TableViewService;
import com.adpilot.modules.tableview.vo.ColumnConfigVo;
import com.adpilot.modules.tableview.vo.SavedViewVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link TableViewService} implementation.
 *
 * <p>Every read and write is filtered by the current authenticated user id
 * (resolved from {@link SecurityUtils#getCurrentUserId()}), so saved views and
 * column configs are isolated per user and store-independent — a record is
 * never returned to, or mutated by, another user (Req 2.12, 17.5).</p>
 *
 * <p>Saved-view name validation (1–100 characters, unique per
 * {@code (user, tableKey)}) runs before any write inside a
 * {@link Transactional} method, so a rejected save leaves existing views
 * unchanged (Req 2.11, 2.13).</p>
 *
 * <p><b>Saved_View no-Operation rule (advertising-workspace-rework Req 3.9).</b>
 * A Saved_View change (create/update/delete of a saved view or column config)
 * is never modelled as an advertising {@code Operation}: these write paths
 * create no {@code Operation_Record} and write no operation-log entry. The rule
 * is enforced structurally — the only collaborators of this class are
 * {@link SavedViewMapper} and {@link ColumnConfigMapper}; it holds no reference
 * to the Operation model, {@code OperationRecordService}, {@code OperationMapper},
 * or the {@code AuditService}, so no operation record or operation-log entry can
 * be produced from here. SLF4J {@code log.*} statements below are plain
 * application logging, not an operation-log entry.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableViewServiceImpl implements TableViewService {

    private final SavedViewMapper savedViewMapper;
    private final ColumnConfigMapper columnConfigMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Saved-view name length bounds (Req 2.11, 2.13). */
    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 100;

    /** Table key length bound (mirrors VARCHAR(100) in schema). */
    private static final int TABLE_KEY_MAX = 100;

    /** Default JSON config when none supplied (matches schema JSON_OBJECT default). */
    private static final String EMPTY_CONFIG = "{}";

    // --- Saved views -------------------------------------------------------

    @Override
    public List<SavedViewVo> list(String tableKey) {
        UUID userId = currentUserId();
        String key = requireTableKey(tableKey);

        LambdaQueryWrapper<SavedViewEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SavedViewEntity::getUserId, userId);
        wrapper.eq(SavedViewEntity::getTableKey, key);
        wrapper.orderByAsc(SavedViewEntity::getName);

        List<SavedViewEntity> views = savedViewMapper.selectList(wrapper);
        if (views == null || views.isEmpty()) {
            return List.of();
        }
        return views.stream().map(this::toVo).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SavedViewVo save(SavedViewDto dto) {
        if (dto == null) {
            throw new BusinessException("SAVED_VIEW_INVALID", "Saved view payload is required");
        }
        UUID userId = currentUserId();
        String key = requireTableKey(dto.getTableKey());
        String name = validateName(dto.getName());

        // Reject a duplicate name for the same (user, tableKey) without altering
        // existing views (Req 2.13).
        LambdaQueryWrapper<SavedViewEntity> dup = new LambdaQueryWrapper<>();
        dup.eq(SavedViewEntity::getUserId, userId);
        dup.eq(SavedViewEntity::getTableKey, key);
        dup.eq(SavedViewEntity::getName, name);
        if (savedViewMapper.selectCount(dup) > 0) {
            throw new BusinessException("SAVED_VIEW_DUPLICATE",
                    "A saved view named '" + name + "' already exists for this table");
        }

        SavedViewEntity entity = SavedViewEntity.builder()
                .userId(userId)
                .tableKey(key)
                .name(name)
                .config(dto.getConfig() != null ? dto.getConfig() : EMPTY_CONFIG)
                .build();

        savedViewMapper.insert(entity);
        log.info("Saved view created: id={}, userId={}, tableKey={}", entity.getId(), userId, key);

        SavedViewEntity saved = savedViewMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    @Override
    @Transactional
    public void delete(String id) {
        UUID userId = currentUserId();
        UUID viewId = parseId(id, "SAVED_VIEW_NOT_FOUND", "Saved view");

        SavedViewEntity existing = savedViewMapper.selectById(viewId);
        if (existing == null || !userId.equals(existing.getUserId())) {
            // Another user's (or absent) record is indistinguishable from not found (Req 17.5).
            throw new BusinessException("SAVED_VIEW_NOT_FOUND", "Saved view not found: " + id);
        }
        savedViewMapper.deleteById(viewId);
        log.info("Saved view deleted: id={}, userId={}", viewId, userId);
    }

    // --- Column configuration ---------------------------------------------

    @Override
    public ColumnConfigVo getColumns(String tableKey) {
        UUID userId = currentUserId();
        String key = requireTableKey(tableKey);

        ColumnConfigEntity existing = findColumnConfig(userId, key);
        return existing != null ? toVo(existing) : null;
    }

    @Override
    @Transactional
    public ColumnConfigVo saveColumns(ColumnConfigDto dto) {
        if (dto == null) {
            throw new BusinessException("COLUMN_CONFIG_INVALID", "Column configuration payload is required");
        }
        UUID userId = currentUserId();
        String key = requireTableKey(dto.getTableKey());
        String config = dto.getConfig() != null ? dto.getConfig() : EMPTY_CONFIG;

        ColumnConfigEntity existing = findColumnConfig(userId, key);
        if (existing != null) {
            existing.setConfig(config);
            columnConfigMapper.updateById(existing);
            log.info("Column config updated: id={}, userId={}, tableKey={}", existing.getId(), userId, key);
            ColumnConfigEntity saved = columnConfigMapper.selectById(existing.getId());
            return toVo(saved != null ? saved : existing);
        }

        ColumnConfigEntity entity = ColumnConfigEntity.builder()
                .userId(userId)
                .tableKey(key)
                .config(config)
                .build();
        columnConfigMapper.insert(entity);
        log.info("Column config created: id={}, userId={}, tableKey={}", entity.getId(), userId, key);

        ColumnConfigEntity saved = columnConfigMapper.selectById(entity.getId());
        return toVo(saved != null ? saved : entity);
    }

    // --- helpers -----------------------------------------------------------

    private ColumnConfigEntity findColumnConfig(UUID userId, String tableKey) {
        LambdaQueryWrapper<ColumnConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ColumnConfigEntity::getUserId, userId);
        wrapper.eq(ColumnConfigEntity::getTableKey, tableKey);
        wrapper.last("LIMIT 1");
        return columnConfigMapper.selectOne(wrapper);
    }

    private UUID currentUserId() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("AUTH_001", "No authenticated user found");
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("AUTH_001", "User id is not a valid identifier: " + userId);
        }
    }

    private String requireTableKey(String tableKey) {
        if (tableKey == null || tableKey.trim().isEmpty()) {
            throw new BusinessException("TABLE_KEY_INVALID", "Table key is required");
        }
        String trimmed = tableKey.trim();
        if (trimmed.length() > TABLE_KEY_MAX) {
            throw new BusinessException("TABLE_KEY_INVALID",
                    "Table key must be at most " + TABLE_KEY_MAX + " characters");
        }
        return trimmed;
    }

    /**
     * Validate that the saved-view name is non-empty and at most 100 characters
     * (Req 2.13). Returns the trimmed name.
     */
    private String validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("SAVED_VIEW_INVALID", "Saved view name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() < NAME_MIN || trimmed.length() > NAME_MAX) {
            throw new BusinessException("SAVED_VIEW_INVALID",
                    "Saved view name must be between " + NAME_MIN + " and " + NAME_MAX + " characters");
        }
        return trimmed;
    }

    private UUID parseId(String id, String code, String label) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(code, label + " id is required");
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(code, label + " not found: " + id);
        }
    }

    private SavedViewVo toVo(SavedViewEntity entity) {
        return SavedViewVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .userId(entity.getUserId() != null ? entity.getUserId().toString() : null)
                .tableKey(entity.getTableKey())
                .name(entity.getName())
                .config(entity.getConfig())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }

    private ColumnConfigVo toVo(ColumnConfigEntity entity) {
        return ColumnConfigVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .userId(entity.getUserId() != null ? entity.getUserId().toString() : null)
                .tableKey(entity.getTableKey())
                .config(entity.getConfig())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(DATETIME_FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(DATETIME_FORMATTER) : null)
                .build();
    }
}
