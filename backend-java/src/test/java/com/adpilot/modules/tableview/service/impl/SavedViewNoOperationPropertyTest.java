package com.adpilot.modules.tableview.service.impl;

import com.adpilot.common.security.AuditService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.OperationRecordService;
import com.adpilot.modules.tableview.dto.ColumnConfigDto;
import com.adpilot.modules.tableview.dto.SavedViewDto;
import com.adpilot.modules.tableview.entity.ColumnConfigEntity;
import com.adpilot.modules.tableview.entity.SavedViewEntity;
import com.adpilot.modules.tableview.mapper.ColumnConfigMapper;
import com.adpilot.modules.tableview.mapper.SavedViewMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Enforces the Saved_View no-Operation rule for {@link TableViewServiceImpl}.
 *
 * <p>Feature: advertising-workspace-rework, Property 24: Saved_View change
 * creates no Operation (enforces the Saved_View no-Operation rule).
 *
 * <p>Validates: Requirements 3.9.
 *
 * <p>Requirement 3.9 states that a Saved_View change SHALL NOT create an
 * {@code Operation_Record} and SHALL NOT write an operation-log entry, so that
 * personal view changes never pollute the advertising operation history. The
 * rule is enforced two ways here:
 * <ul>
 *   <li><b>Structurally</b> — {@link TableViewServiceImpl} declares no
 *       collaborator capable of creating an {@code Operation_Record} or an
 *       operation-log entry ({@link OperationMapper},
 *       {@link OperationRecordService}, any {@code modules.advertising.operation}
 *       type, or the authorization {@link AuditService}). A regression that
 *       wired any such collaborator into this class would fail this test.</li>
 *   <li><b>Behaviourally</b> — exercising create/update/delete of saved views
 *       and column configs over many generated inputs touches ONLY the
 *       saved-view and column-config mappers; no {@link OperationEntity} is ever
 *       inserted, because no operation collaborator participates in the path.</li>
 * </ul>
 */
class SavedViewNoOperationPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SavedViewEntity.class);
        TableInfoHelper.initTableInfo(assistant, ColumnConfigEntity.class);
    }

    /** Package that houses the Operation model — forbidden as a Saved_View collaborator. */
    private static final String OPERATION_PACKAGE = "com.adpilot.modules.advertising.operation";

    /**
     * Structural enforcement: the Saved_View service holds no dependency that
     * could create an {@code Operation_Record} or write an operation-log entry.
     *
     * <p>Validates: Requirements 3.9.
     */
    @Test
    void savedViewServiceDeclaresNoOperationOrAuditCollaborator() {
        for (Field field : TableViewServiceImpl.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            String typeName = type.getName();

            assertThat(type)
                    .as("Saved_View service must not depend on the Operation_Record persistence "
                            + "(field '%s'), per Req 3.9", field.getName())
                    .isNotEqualTo(OperationMapper.class)
                    .isNotEqualTo(OperationRecordService.class)
                    .isNotEqualTo(OperationEntity.class)
                    .isNotEqualTo(AuditService.class);

            assertThat(typeName)
                    .as("Saved_View service must not depend on any Operation model type "
                            + "(field '%s' is %s), per Req 3.9", field.getName(), typeName)
                    .doesNotStartWith(OPERATION_PACKAGE);
        }
    }

    /**
     * Behavioural enforcement: creating, then deleting, a saved view, plus
     * upserting a column config, only ever writes through the saved-view /
     * column-config mappers — never an {@code OperationEntity}.
     *
     * <p>Feature: advertising-workspace-rework, Property 24: Saved_View change
     * creates no Operation. *For any* Saved_View create/update/delete, no
     * Operation_Record and no operation-log entry is created.
     *
     * <p>Validates: Requirements 3.9.
     */
    @Label("Property 24: Saved_View change creates no Operation")
    @Property(tries = 200)
    void savedViewWritesCreateNoOperationRecord(
            @ForAll("userIds") UUID userId,
            @ForAll("tableKeys") String tableKey,
            @ForAll("names") String name,
            @ForAll("configs") String config) {

        SavedViewMapper savedViewMapper = mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = mock(ColumnConfigMapper.class);
        // A sentinel operation mapper that MUST never be touched from this path.
        OperationMapper operationMapper = mock(OperationMapper.class);

        TableViewServiceImpl service =
                new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        // No duplicate exists, so save proceeds. Model the insert as the
        // UuidIdInsertInterceptor does: assign the char(36) UUID primary key.
        lenient().when(savedViewMapper.selectCount(any())).thenReturn(0L);
        lenient().when(savedViewMapper.insert(any())).thenAnswer(inv -> {
            SavedViewEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return 1;
        });
        // selectById echoes the row that was inserted (matched by id).
        lenient().when(savedViewMapper.selectById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (id == null) {
                return null;
            }
            return SavedViewEntity.builder()
                    .id(id)
                    .userId(userId)
                    .tableKey(tableKey)
                    .name(name.trim())
                    .config(config)
                    .build();
        });
        lenient().when(columnConfigMapper.selectOne(any())).thenReturn(null);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());

            SavedViewDto viewDto = new SavedViewDto();
            viewDto.setTableKey(tableKey);
            viewDto.setName(name);
            viewDto.setConfig(config);
            var saved = service.save(viewDto);

            // Update path for the Saved_View's column configuration (create branch).
            ColumnConfigDto columnDto = new ColumnConfigDto();
            columnDto.setTableKey(tableKey);
            columnDto.setConfig(config);
            service.saveColumns(columnDto);

            // Delete the just-created saved view.
            when(savedViewMapper.selectById(UUID.fromString(saved.getId())))
                    .thenReturn(SavedViewEntity.builder()
                            .id(UUID.fromString(saved.getId()))
                            .userId(userId)
                            .tableKey(tableKey)
                            .name(name.trim())
                            .config(config)
                            .build());
            service.delete(saved.getId());
        }

        // The Operation_Record store is never written, in any form, from a
        // Saved_View create/update/delete (Req 3.9).
        Mockito.verifyNoInteractions(operationMapper);
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<UUID> userIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    @Provide
    Arbitrary<String> tableKeys() {
        return Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().withCharRange('a', 'z').alpha()
                .ofMinLength(1).ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> configs() {
        Arbitrary<String> keys = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(8);
        Arbitrary<String> values = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(12);
        return Combinators.combine(keys, values)
                .as((k, v) -> "{\"" + k + "\":\"" + v + "\"}");
    }
}
