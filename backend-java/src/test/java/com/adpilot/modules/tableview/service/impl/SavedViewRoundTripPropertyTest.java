package com.adpilot.modules.tableview.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.tableview.dto.SavedViewDto;
import com.adpilot.modules.tableview.entity.SavedViewEntity;
import com.adpilot.modules.tableview.mapper.ColumnConfigMapper;
import com.adpilot.modules.tableview.mapper.SavedViewMapper;
import com.adpilot.modules.tableview.vo.SavedViewVo;
import com.adpilot.common.utils.SecurityUtils;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link TableViewServiceImpl#save(SavedViewDto)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 17: Saved views
 * round-trip and reject invalid or duplicate names.
 *
 * <p>Validates: Requirements 2.11, 2.13, 2.14.
 *
 * <p>For any saved view persisted under a valid name (1–100 characters, unique
 * for the user and table key), the returned view reapplies exactly the submitted
 * column configuration, filter set, and sort order (the {@code config} JSON) plus
 * the name and table key, with an id assigned (Req 2.11, 2.14). For any save
 * attempt with an empty name, a name exceeding 100 characters, or a name that
 * duplicates an existing view for the same {@code (user, tableKey)}, the save is
 * rejected with a {@link BusinessException} and no {@code insert} write occurs,
 * so existing views are left unchanged (Req 2.13).
 */
class SavedViewRoundTripPropertyTest {

    /**
     * Feature: platform-ux-logistics-enhancements, Property 17: Saved views
     * round-trip and reject invalid or duplicate names.
     *
     * <p>Validates: Requirements 2.11, 2.14.
     *
     * <p>Saving a valid, unique saved view round-trips: the persisted view
     * carries the submitted name, table key, and config, with an id assigned.
     */
    @Property(tries = 200)
    void savingValidUniqueViewRoundTrips(
            @ForAll("validNames") String name,
            @ForAll("tableKeys") String tableKey,
            @ForAll("configs") String config) {

        SavedViewMapper savedViewMapper = Mockito.mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = Mockito.mock(ColumnConfigMapper.class);
        TableViewServiceImpl service = new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        UUID userId = UUID.randomUUID();

        // No duplicate exists for this (user, tableKey, name).
        when(savedViewMapper.selectCount(any())).thenReturn(0L);

        // Simulate id generation on insert, then feed the persisted entity back.
        UUID generatedId = UUID.randomUUID();
        when(savedViewMapper.insert(any(SavedViewEntity.class))).thenAnswer(invocation -> {
            SavedViewEntity e = invocation.getArgument(0);
            e.setId(generatedId);
            return 1;
        });
        when(savedViewMapper.selectById(generatedId)).thenAnswer(
                invocation -> capturedInsert(savedViewMapper, generatedId));

        SavedViewDto dto = new SavedViewDto();
        dto.setName(name);
        dto.setTableKey(tableKey);
        dto.setConfig(config);

        SavedViewVo result;
        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());
            result = service.save(dto);
        }

        // Round-trip: submitted name, table key, and config are reapplied.
        assertThat(result.getName()).isEqualTo(name.trim());
        assertThat(result.getTableKey()).isEqualTo(tableKey.trim());
        assertThat(result.getConfig()).isEqualTo(config);
        assertThat(result.getUserId()).isEqualTo(userId.toString());
        // Id assigned.
        assertThat(result.getId()).isEqualTo(generatedId.toString());
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 17: Saved views
     * round-trip and reject invalid or duplicate names.
     *
     * <p>Validates: Requirements 2.13.
     *
     * <p>Saving with an empty name, a name exceeding 100 characters, or a name
     * duplicating an existing view for the same {@code (user, tableKey)} is
     * rejected with a {@link BusinessException}; no insert occurs, so existing
     * views are unchanged.
     */
    @Property(tries = 200)
    void savingInvalidOrDuplicateNameIsRejectedAndLeavesViewsUnchanged(
            @ForAll("invalidOrDuplicateCases") InvalidCase testCase,
            @ForAll("tableKeys") String tableKey,
            @ForAll("configs") String config) {

        SavedViewMapper savedViewMapper = Mockito.mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = Mockito.mock(ColumnConfigMapper.class);
        TableViewServiceImpl service = new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        UUID userId = UUID.randomUUID();

        // For the duplicate case the count check returns > 0; for invalid-name
        // cases the count would never be consulted (rejection happens first), so a
        // lenient stub keeps both paths valid.
        Mockito.lenient().when(savedViewMapper.selectCount(any()))
                .thenReturn(testCase.duplicate ? 1L : 0L);

        SavedViewDto dto = new SavedViewDto();
        dto.setName(testCase.name);
        dto.setTableKey(tableKey);
        dto.setConfig(config);

        try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(userId.toString());

            assertThatThrownBy(() -> service.save(dto))
                    .isInstanceOf(BusinessException.class);
        }

        // Existing views unchanged: no write was attempted.
        verify(savedViewMapper, never()).insert(any());
    }

    private static SavedViewEntity capturedInsert(SavedViewMapper mapper, UUID id) {
        ArgumentCaptor<SavedViewEntity> captor = ArgumentCaptor.forClass(SavedViewEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    // --- Generators ---------------------------------------------------------

    /**
     * Valid names: 1–100 characters after trimming. Generated from non-blank,
     * non-whitespace characters so the trimmed length stays within bounds.
     */
    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ ")
                .ofMinLength(1).ofMaxLength(100)
                .filter(s -> !s.trim().isEmpty() && s.trim().length() <= 100);
    }

    /** Table keys within the 1–100 bound. */
    @Provide
    Arbitrary<String> tableKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(100);
    }

    /** Config JSON payloads, including the empty-object default. */
    @Provide
    Arbitrary<String> configs() {
        return Arbitraries.of(
                "{}",
                "{\"columns\":[\"a\",\"b\"]}",
                "{\"filters\":[{\"field\":\"x\",\"op\":\"eq\",\"value\":1}],\"sort\":[{\"field\":\"x\",\"dir\":\"asc\"}]}",
                "{\"columns\":[\"id\",\"name\",\"createdAt\"],\"sort\":[{\"field\":\"name\",\"dir\":\"desc\"}]}");
    }

    /** Invalid-name and duplicate-name rejection cases. */
    @Provide
    Arbitrary<InvalidCase> invalidOrDuplicateCases() {
        // Empty / whitespace-only names.
        Arbitrary<InvalidCase> empty = Arbitraries.of("", "   ", "\t", "\n  ")
                .map(s -> new InvalidCase(s, false));
        // Names exceeding 100 characters after trimming.
        Arbitrary<InvalidCase> tooLong = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(101).ofMaxLength(300)
                .map(s -> new InvalidCase(s, false));
        // Valid-length names that collide with an existing view (selectCount > 0).
        Arbitrary<InvalidCase> duplicate = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(100)
                .map(s -> new InvalidCase(s, true));
        return Arbitraries.oneOf(empty, tooLong, duplicate);
    }

    /** A rejection case: the submitted name plus whether it duplicates an existing view. */
    static final class InvalidCase {
        final String name;
        final boolean duplicate;

        InvalidCase(String name, boolean duplicate) {
            this.name = name;
            this.duplicate = duplicate;
        }

        @Override
        public String toString() {
            return "InvalidCase{name.length=" + (name == null ? "null" : name.length())
                    + ", duplicate=" + duplicate + "}";
        }
    }
}
