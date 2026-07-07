package com.adpilot.modules.tableview.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.tableview.entity.ColumnConfigEntity;
import com.adpilot.modules.tableview.entity.SavedViewEntity;
import com.adpilot.modules.tableview.mapper.ColumnConfigMapper;
import com.adpilot.modules.tableview.mapper.SavedViewMapper;
import com.adpilot.modules.tableview.vo.ColumnConfigVo;
import com.adpilot.modules.tableview.vo.SavedViewVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for per-user isolation of saved views and column configs
 * served by {@link TableViewServiceImpl}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 21: Saved views and
 * column configs are isolated per user.
 *
 * <p>Validates: Requirements 2.12, 17.5.
 *
 * <p>Saved views and column configs are keyed by {@code (user_id, table_key)},
 * store-independent, and never readable by another user. The acting user is
 * resolved server-side via {@link SecurityUtils#getCurrentUserId()} and every
 * read/write is filtered by that id. These properties assert that:
 * <ul>
 *   <li>The {@link LambdaQueryWrapper} the service hands to the mapper is always
 *       scoped to the acting user's id (and never another user's id); and</li>
 *   <li>A record owned by a different user is never returned: modelling the
 *       mapper as a user-scoped store, cross-user {@code list}/{@code getColumns}
 *       reads come back empty/{@code null} and a cross-user {@code delete} is
 *       indistinguishable from not-found.</li>
 * </ul>
 */
class TableViewIsolationPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and
        // materialise bound parameter values for query-wrapper introspection,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SavedViewEntity.class);
        TableInfoHelper.initTableInfo(assistant, ColumnConfigEntity.class);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 21: Saved views and
     * column configs are isolated per user.
     *
     * <p>Validates: Requirements 2.12, 17.5.
     *
     * <p>{@code list} scopes its query to the acting user's id and never returns
     * saved views owned by another user.
     */
    @Property(tries = 200)
    void listSavedViewsIsScopedToActingUserAndExcludesOtherUsers(
            @ForAll("users") List<UUID> users,
            @ForAll("tableKeys") String tableKey) {

        UUID actingUser = users.get(0);
        UUID otherUser = users.get(1);

        SavedViewMapper savedViewMapper = Mockito.mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = Mockito.mock(ColumnConfigMapper.class);
        TableViewServiceImpl service = new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        // A store containing only views owned by the OTHER user.
        List<SavedViewEntity> store = new ArrayList<>();
        store.add(savedView(otherUser, tableKey, "alpha"));
        store.add(savedView(otherUser, tableKey, "beta"));

        // Model the mapper as a user-scoped store: only rows whose user_id matches
        // the wrapper's user filter are visible.
        when(savedViewMapper.selectList(any())).thenAnswer(inv ->
                store.stream()
                        .filter(v -> v.getUserId().equals(scopedUserId(inv)))
                        .filter(v -> v.getTableKey().equals(tableKey))
                        .collect(Collectors.toList()));

        List<SavedViewVo> result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(actingUser.toString());
            result = service.list(tableKey);
        }

        // Cross-user read returns nothing: the other user's views are never leaked.
        assertThat(result).isEmpty();

        // The wrapper handed to the mapper is scoped to the acting user, not the other.
        assertThat(capturedSavedViewFilterValues(savedViewMapper))
                .contains(actingUser)
                .doesNotContain(otherUser);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 21: Saved views and
     * column configs are isolated per user.
     *
     * <p>Validates: Requirements 2.12, 17.5.
     *
     * <p>{@code getColumns} scopes its query to the acting user's id and never
     * returns a column config owned by another user.
     */
    @Property(tries = 200)
    void getColumnsIsScopedToActingUserAndExcludesOtherUsers(
            @ForAll("users") List<UUID> users,
            @ForAll("tableKeys") String tableKey) {

        UUID actingUser = users.get(0);
        UUID otherUser = users.get(1);

        SavedViewMapper savedViewMapper = Mockito.mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = Mockito.mock(ColumnConfigMapper.class);
        TableViewServiceImpl service = new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        // The only stored config belongs to the OTHER user.
        ColumnConfigEntity foreign = columnConfig(otherUser, tableKey);

        when(columnConfigMapper.selectOne(any())).thenAnswer(inv -> {
            UUID scoped = scopedUserId(inv);
            return foreign.getUserId().equals(scoped) && foreign.getTableKey().equals(tableKey)
                    ? foreign : null;
        });

        ColumnConfigVo result;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(actingUser.toString());
            result = service.getColumns(tableKey);
        }

        // Another user's config is never readable.
        assertThat(result).isNull();

        assertThat(capturedColumnConfigFilterValues(columnConfigMapper))
                .contains(actingUser)
                .doesNotContain(otherUser);
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 21: Saved views and
     * column configs are isolated per user.
     *
     * <p>Validates: Requirements 2.12, 17.5.
     *
     * <p>Deleting a saved view owned by another user is rejected as not-found and
     * performs no delete, so a user can neither read nor mutate another user's view.
     */
    @Property(tries = 200)
    void deletingAnotherUsersSavedViewIsNotFoundAndPerformsNoDelete(
            @ForAll("users") List<UUID> users,
            @ForAll("tableKeys") String tableKey) {

        UUID actingUser = users.get(0);
        UUID otherUser = users.get(1);

        SavedViewMapper savedViewMapper = Mockito.mock(SavedViewMapper.class);
        ColumnConfigMapper columnConfigMapper = Mockito.mock(ColumnConfigMapper.class);
        TableViewServiceImpl service = new TableViewServiceImpl(savedViewMapper, columnConfigMapper);

        SavedViewEntity foreign = savedView(otherUser, tableKey, "other-owned");
        when(savedViewMapper.selectById(foreign.getId())).thenReturn(foreign);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(actingUser.toString());

            assertThatThrownBy(() -> service.delete(foreign.getId().toString()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("not found");
        }

        verify(savedViewMapper, never()).deleteById(any(UUID.class));
        // The other user's record is left untouched.
        assertThat(foreign.getUserId()).isEqualTo(otherUser);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Extract the user-id value the service placed into the {@link LambdaQueryWrapper}
     * passed to a {@code selectList}/{@code selectOne} call, so the mocked mapper can
     * filter its store exactly as a user-scoped query would.
     */
    private static UUID scopedUserId(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        return filterValues(wrapper).stream()
                .filter(v -> v instanceof UUID)
                .map(UUID.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static List<Object> capturedSavedViewFilterValues(SavedViewMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<SavedViewEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        return filterValues(captor.getValue());
    }

    private static List<Object> capturedColumnConfigFilterValues(ColumnConfigMapper mapper) {
        ArgumentCaptor<LambdaQueryWrapper<ColumnConfigEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        return filterValues(captor.getValue());
    }

    private static List<Object> filterValues(LambdaQueryWrapper<?> wrapper) {
        // MyBatis-Plus materialises bound parameter values lazily while building the
        // SQL segment, so force segment generation before reading the pairs.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return new ArrayList<>(pairs.values());
    }

    private static SavedViewEntity savedView(UUID userId, String tableKey, String name) {
        return SavedViewEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tableKey(tableKey)
                .name(name)
                .config("{}")
                .build();
    }

    private static ColumnConfigEntity columnConfig(UUID userId, String tableKey) {
        return ColumnConfigEntity.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tableKey(tableKey)
                .config("{}")
                .build();
    }

    // --- generators --------------------------------------------------------

    /** At least two distinct users so an "acting" and an "other" user always exist. */
    @Provide
    Arbitrary<List<UUID>> users() {
        return Arbitraries.randomValue(r -> UUID.randomUUID())
                .list().ofMinSize(2).ofMaxSize(4).uniqueElements();
    }

    /** Valid table keys: non-blank, 1-100 chars (mirrors the VARCHAR(100) bound). */
    @Provide
    Arbitrary<String> tableKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1)
                .ofMaxLength(100);
    }
}
