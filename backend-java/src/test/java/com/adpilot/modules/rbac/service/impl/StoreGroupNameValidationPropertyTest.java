package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for Store_Group name validation performed by
 * {@link StoreGroupServiceImpl#create(CreateStoreGroupCommand)}.
 *
 * <p>Feature: platform-workspace-rbac, Property 11: Store-group name validation.
 *
 * <p>Validates: Requirements 10.1, 10.4.
 *
 * <p>For any proposed Store_Group name and platform family, creation is rejected
 * when the name is empty (after trimming), exceeds 100 characters, or duplicates
 * an existing Store_Group name within the same platform family, and is accepted
 * otherwise. The duplicate check is modelled by stubbing
 * {@link StoreGroupMapper#selectCount} against an in-memory set of existing names
 * extracted from the {@link LambdaQueryWrapper} the service hands to the mapper.
 */
class StoreGroupNameValidationPropertyTest {

    private static final int MAX_NAME_LENGTH = 100;

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and
        // materialise bound parameter values for query-wrapper introspection,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StoreGroupEntity.class);
    }

    /** A generated case: the raw name to submit and whether creation must be rejected. */
    record NameCase(String rawName, boolean shouldReject, boolean isDuplicate) {
    }

    /**
     * Feature: platform-workspace-rbac, Property 11: Store-group name validation.
     *
     * <p>Validates: Requirements 10.1, 10.4.
     */
    @Property(tries = 200)
    void createRejectsInvalidOrDuplicateNamesAndAcceptsOtherwise(
            @ForAll("nameCases") NameCase nameCase,
            @ForAll("storeGroupFamilies") PlatformFamily family) {

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        StoreGroupMapper storeGroupMapper = Mockito.mock(StoreGroupMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        StoreGroupServiceImpl service = new StoreGroupServiceImpl(storeGroupMapper, storeMapper);

        // Model existing Store_Group names within this org+family. Only the
        // duplicate case seeds an existing row whose name collides with the input.
        Set<String> existingNames = new HashSet<>();
        if (nameCase.isDuplicate()) {
            existingNames.add(nameCase.rawName().trim());
        }

        // selectCount returns > 0 only when the queried name matches an existing
        // name, mirroring the unique (org_id, platform_family, name) constraint.
        when(storeGroupMapper.selectCount(any())).thenAnswer(inv ->
                existingNames.contains(queriedName(inv)) ? 1L : 0L);

        CreateStoreGroupCommand cmd = new CreateStoreGroupCommand();
        cmd.setName(nameCase.rawName());
        cmd.setPlatformFamily(family);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(userId.toString());

            if (nameCase.shouldReject()) {
                assertThatThrownBy(() -> service.create(cmd))
                        .isInstanceOf(BusinessException.class);
            } else {
                StoreGroupVo vo = service.create(cmd);
                // Accepted: the trimmed name is persisted under the requested family.
                assertThat(vo).isNotNull();
                assertThat(vo.getName()).isEqualTo(nameCase.rawName().trim());
                assertThat(vo.getPlatformFamily()).isEqualTo(family.getCode());
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Extract the {@code name} value the service placed into the
     * {@link LambdaQueryWrapper} passed to {@code selectCount}. The wrapper also
     * binds the org id (a UUID) and the family code, so we pick the first String
     * value that is not a known family code.
     */
    private static String queriedName(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return pairs.values().stream()
                .filter(v -> v instanceof String)
                .map(String.class::cast)
                .filter(s -> !isFamilyCode(s))
                .findFirst()
                .orElse(null);
    }

    private static boolean isFamilyCode(String value) {
        for (PlatformFamily family : PlatformFamily.values()) {
            if (family.getCode().equals(value)) {
                return true;
            }
        }
        return false;
    }

    // --- generators --------------------------------------------------------

    /** Store_Group families: only {@code amazon} and {@code independent_site} (Req 10.1). */
    @Provide
    Arbitrary<PlatformFamily> storeGroupFamilies() {
        return Arbitraries.of(PlatformFamily.AMAZON, PlatformFamily.INDEPENDENT_SITE);
    }

    /** Valid name body: 1-100 non-whitespace-trimmable chars, never a family code. */
    private Arbitrary<String> validNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '0', '9', '-', '_')
                .ofMinLength(1)
                .ofMaxLength(MAX_NAME_LENGTH)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() <= MAX_NAME_LENGTH && !isFamilyCode(s));
    }

    /**
     * A mix of name cases:
     * <ul>
     *   <li>empty / whitespace-only &rarr; rejected (Req 10.1);</li>
     *   <li>over-length (&gt; 100 chars) &rarr; rejected (Req 10.4);</li>
     *   <li>valid, unique &rarr; accepted;</li>
     *   <li>valid but duplicate of an existing name &rarr; rejected (Req 10.4).</li>
     * </ul>
     */
    @Provide
    Arbitrary<NameCase> nameCases() {
        Arbitrary<NameCase> emptyOrBlank = Arbitraries.strings()
                .withChars(' ', '\t', '\n')
                .ofMinLength(0)
                .ofMaxLength(6)
                .map(s -> new NameCase(s, true, false));

        Arbitrary<NameCase> overLength = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(MAX_NAME_LENGTH + 1)
                .ofMaxLength(MAX_NAME_LENGTH + 50)
                .map(s -> new NameCase(s, true, false));

        Arbitrary<NameCase> validUnique = validNames()
                .map(s -> new NameCase(s, false, false));

        Arbitrary<NameCase> duplicate = validNames()
                .map(s -> new NameCase(s, true, true));

        return Arbitraries.oneOf(emptyOrBlank, overLength, validUnique, duplicate);
    }
}
