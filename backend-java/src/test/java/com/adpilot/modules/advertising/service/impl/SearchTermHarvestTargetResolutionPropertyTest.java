package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for harvest target derivation and scoped override in
 * {@link SearchTermHarvestServiceImpl}.
 *
 * <p>Feature: advertising-workspace-rework, Property 32: Harvest target
 * derivation and scoped override.
 *
 * <p>Validates: Requirements 13.1, 13.2.
 *
 * <p>The subject resolves the target Campaign/Ad_Group for a harvest. The single
 * named property has the following facets, each universally quantified and asserted
 * below:
 * <ul>
 *   <li><b>13.1 derivation</b> — with no override supplied, the resolved target is
 *       the Campaign and Ad_Group associated with the Search_Term, and no data-scope
 *       check is performed against the derived target.</li>
 *   <li><b>13.2 scoped override</b> — when an override is supplied it is used
 *       instead of the derived target, and BOTH the override Campaign and override
 *       Ad_Group are validated against the caller's effective data scope; an override
 *       outside scope is rejected and produces no Keyword.</li>
 *   <li><b>13.2 ad-group/campaign consistency</b> — an override Ad_Group that does
 *       not belong to the resolved Campaign is rejected and produces no Keyword.</li>
 *   <li><b>13.1/13.2 unresolvable</b> — a target that cannot be resolved (override
 *       campaign that does not exist, or a derivation with no associated campaign)
 *       is rejected with {@code HARVEST_TARGET_UNRESOLVABLE} and produces no Keyword.</li>
 * </ul>
 *
 * <p>The mappers and {@link DataScopeService} are mocked; {@link SecurityUtils} is
 * statically stubbed to present an authenticated caller so the override path
 * exercises data-scope validation.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 32: Harvest target derivation and scoped override")
class SearchTermHarvestTargetResolutionPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 32: Harvest target derivation
     * and scoped override.
     *
     * <p>Validates: Requirements 13.1.
     *
     * <p>With no override, the created Keyword targets the Campaign and Ad_Group
     * derived from the Search_Term, and no data-scope check runs against a derived
     * target.
     */
    @Property(tries = 200)
    @Label("Property 32: no override derives target campaign and ad group from the search term")
    void noOverrideDerivesTargetFromSearchTerm(
            @ForAll("searchTermText") String term,
            @ForAll("keywordActions") String action) {

        Fixture f = new Fixture();
        UUID stId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID adGroupId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermEntity st = searchTerm(stId, campaignId, adGroupId, storeId, term);
        when(f.searchTermMapper.selectById(stId)).thenReturn(st);
        when(f.campaignMapper.selectById(campaignId)).thenReturn(campaign(campaignId, storeId));
        when(f.adGroupMapper.selectById(adGroupId)).thenReturn(adGroup(adGroupId, campaignId, storeId));

        SearchTermHarvestRequest req = request(action, null, null);

        try (MockedStatic<SecurityUtils> su = authenticatedAs(user())) {
            f.service.harvest(stId.toString(), req, UUID.randomUUID().toString());
        }

        KeywordEntity inserted = captureInsertedKeyword(f.keywordMapper);
        assertThat(inserted.getCampaignId())
                .as("derived target campaign must come from the search term")
                .isEqualTo(campaignId);
        assertThat(inserted.getAdGroupId())
                .as("derived target ad group must come from the search term")
                .isEqualTo(adGroupId);
        // A derived target is the operator's own associated objects; no scope check is performed.
        verify(f.dataScopeService, never()).assertCanWrite(any(), any());
    }

    /**
     * Feature: advertising-workspace-rework, Property 32: Harvest target derivation
     * and scoped override.
     *
     * <p>Validates: Requirements 13.2.
     *
     * <p>When an override target is supplied, the created Keyword targets the
     * override (never the derived target), and both the override Campaign and override
     * Ad_Group are validated against the caller's effective data scope.
     */
    @Property(tries = 200)
    @Label("Property 32: a supplied override target is used and validated against the caller's data scope")
    void overrideTargetIsUsedAndScopeValidated(
            @ForAll("searchTermText") String term,
            @ForAll("keywordActions") String action) {

        Fixture f = new Fixture();
        UUID stId = UUID.randomUUID();
        UUID derivedCampaignId = UUID.randomUUID();
        UUID derivedAdGroupId = UUID.randomUUID();
        UUID overrideCampaignId = UUID.randomUUID();
        UUID overrideAdGroupId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermEntity st = searchTerm(stId, derivedCampaignId, derivedAdGroupId, storeId, term);
        CampaignEntity overrideCampaign = campaign(overrideCampaignId, storeId);
        AdGroupEntity overrideAdGroup = adGroup(overrideAdGroupId, overrideCampaignId, storeId);

        when(f.searchTermMapper.selectById(stId)).thenReturn(st);
        when(f.campaignMapper.selectById(overrideCampaignId)).thenReturn(overrideCampaign);
        when(f.adGroupMapper.selectById(overrideAdGroupId)).thenReturn(overrideAdGroup);

        SearchTermHarvestRequest req = request(action, overrideCampaignId.toString(), overrideAdGroupId.toString());

        CurrentUser caller = user();
        try (MockedStatic<SecurityUtils> su = authenticatedAs(caller)) {
            f.service.harvest(stId.toString(), req, UUID.randomUUID().toString());
        }

        KeywordEntity inserted = captureInsertedKeyword(f.keywordMapper);
        assertThat(inserted.getCampaignId())
                .as("override campaign must be used instead of the derived campaign")
                .isEqualTo(overrideCampaignId);
        assertThat(inserted.getAdGroupId())
                .as("override ad group must be used instead of the derived ad group")
                .isEqualTo(overrideAdGroupId);
        // Both override objects are validated against the caller's effective data scope.
        verify(f.dataScopeService).assertCanWrite(overrideCampaign, caller);
        verify(f.dataScopeService).assertCanWrite(overrideAdGroup, caller);
    }

    /**
     * Feature: advertising-workspace-rework, Property 32: Harvest target derivation
     * and scoped override.
     *
     * <p>Validates: Requirements 13.2.
     *
     * <p>An override target outside the caller's effective data scope is rejected and
     * no Keyword is created.
     */
    @Property(tries = 200)
    @Label("Property 32: an override target outside the caller's scope is rejected and creates no keyword")
    void overrideOutsideScopeIsRejected(
            @ForAll("searchTermText") String term,
            @ForAll("keywordActions") String action) {

        Fixture f = new Fixture();
        UUID stId = UUID.randomUUID();
        UUID overrideCampaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermEntity st = searchTerm(stId, UUID.randomUUID(), UUID.randomUUID(), storeId, term);
        when(f.searchTermMapper.selectById(stId)).thenReturn(st);
        when(f.campaignMapper.selectById(overrideCampaignId)).thenReturn(campaign(overrideCampaignId, storeId));
        // The shared scope guard refuses the override target.
        doThrow(new BusinessException(403, "DATA_SCOPE_DENIED", "outside scope"))
                .when(f.dataScopeService).assertCanWrite(any(), any());

        SearchTermHarvestRequest req = request(action, overrideCampaignId.toString(), null);

        try (MockedStatic<SecurityUtils> su = authenticatedAs(user())) {
            assertThatThrownBy(() -> f.service.harvest(stId.toString(), req, UUID.randomUUID().toString()))
                    .isInstanceOf(BusinessException.class);
        }

        verify(f.keywordMapper, never()).insert(any());
    }

    /**
     * Feature: advertising-workspace-rework, Property 32: Harvest target derivation
     * and scoped override.
     *
     * <p>Validates: Requirements 13.2.
     *
     * <p>An override Ad_Group that does not belong to the resolved Campaign is
     * rejected and no Keyword is created.
     */
    @Property(tries = 200)
    @Label("Property 32: an override ad group not belonging to the resolved campaign is rejected")
    void overrideAdGroupMustBelongToResolvedCampaign(
            @ForAll("searchTermText") String term,
            @ForAll("keywordActions") String action) {

        Fixture f = new Fixture();
        UUID stId = UUID.randomUUID();
        UUID overrideCampaignId = UUID.randomUUID();
        UUID overrideAdGroupId = UUID.randomUUID();
        UUID foreignCampaignId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermEntity st = searchTerm(stId, UUID.randomUUID(), UUID.randomUUID(), storeId, term);
        when(f.searchTermMapper.selectById(stId)).thenReturn(st);
        when(f.campaignMapper.selectById(overrideCampaignId)).thenReturn(campaign(overrideCampaignId, storeId));
        // The override ad group belongs to a DIFFERENT campaign than the resolved one.
        when(f.adGroupMapper.selectById(overrideAdGroupId))
                .thenReturn(adGroup(overrideAdGroupId, foreignCampaignId, storeId));

        SearchTermHarvestRequest req = request(action, overrideCampaignId.toString(), overrideAdGroupId.toString());

        BusinessException ex;
        try (MockedStatic<SecurityUtils> su = authenticatedAs(user())) {
            ex = catchThrowableOfType(
                    () -> f.service.harvest(stId.toString(), req, UUID.randomUUID().toString()),
                    BusinessException.class);
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("HARVEST_TARGET_UNRESOLVABLE");
        verify(f.keywordMapper, never()).insert(any());
    }

    /**
     * Feature: advertising-workspace-rework, Property 32: Harvest target derivation
     * and scoped override.
     *
     * <p>Validates: Requirements 13.1, 13.2.
     *
     * <p>An unresolvable target — an override campaign that does not exist, or a
     * derivation from a Search_Term with no associated campaign — is rejected with
     * {@code HARVEST_TARGET_UNRESOLVABLE} and creates no Keyword.
     */
    @Property(tries = 200)
    @Label("Property 32: an unresolvable target is rejected and creates no keyword")
    void unresolvableTargetIsRejected(
            @ForAll("searchTermText") String term,
            @ForAll("keywordActions") String action,
            @ForAll boolean useOverride) {

        Fixture f = new Fixture();
        UUID stId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermHarvestRequest req;
        if (useOverride) {
            // Override campaign id is well-formed but resolves to nothing.
            UUID missingCampaignId = UUID.randomUUID();
            SearchTermEntity st = searchTerm(stId, UUID.randomUUID(), UUID.randomUUID(), storeId, term);
            when(f.searchTermMapper.selectById(stId)).thenReturn(st);
            when(f.campaignMapper.selectById(missingCampaignId)).thenReturn(null);
            req = request(action, missingCampaignId.toString(), null);
        } else {
            // No override and the search term carries no campaign to derive from.
            SearchTermEntity st = searchTerm(stId, null, null, storeId, term);
            when(f.searchTermMapper.selectById(stId)).thenReturn(st);
            req = request(action, null, null);
        }

        BusinessException ex;
        try (MockedStatic<SecurityUtils> su = authenticatedAs(user())) {
            ex = catchThrowableOfType(
                    () -> f.service.harvest(stId.toString(), req, UUID.randomUUID().toString()),
                    BusinessException.class);
        }

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("HARVEST_TARGET_UNRESOLVABLE");
        verify(f.keywordMapper, never()).insert(any());
    }

    // --- generators --------------------------------------------------------

    /** Non-blank search-term text within the persisted length bound. */
    @Provide
    Arbitrary<String> searchTermText() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz 0123456789")
                .ofMinLength(1)
                .ofMaxLength(60)
                .filter(s -> !s.isBlank());
    }

    /** The two keyword-producing actions, both of which create a Keyword in the resolved target. */
    @Provide
    Arbitrary<String> keywordActions() {
        return Arbitraries.of("add_exact", "add_phrase");
    }

    // --- fixture & helpers -------------------------------------------------

    /** Fresh mocked collaborators + service per iteration so verify() counts are clean. */
    private static final class Fixture {
        final SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        final CampaignMapper campaignMapper = mock(CampaignMapper.class);
        final AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        final KeywordMapper keywordMapper = mock(KeywordMapper.class);
        final NegativeKeywordMapper negativeKeywordMapper = mock(NegativeKeywordMapper.class);
        final DataScopeService dataScopeService = mock(DataScopeService.class);
        final SearchTermHarvestServiceImpl service = new SearchTermHarvestServiceImpl(
                searchTermMapper, campaignMapper, adGroupMapper, keywordMapper,
                negativeKeywordMapper, dataScopeService);
    }

    /** Stub {@link SecurityUtils} to present an authenticated caller for the override path. */
    private static MockedStatic<SecurityUtils> authenticatedAs(CurrentUser caller) {
        MockedStatic<SecurityUtils> su = mockStatic(SecurityUtils.class);
        su.when(SecurityUtils::isAuthenticated).thenReturn(true);
        su.when(SecurityUtils::getCurrentUser).thenReturn(caller);
        return su;
    }

    private static CurrentUser user() {
        return CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("op@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(Set.of("operations_specialist"))
                .build();
    }

    private static SearchTermEntity searchTerm(UUID id, UUID campaignId, UUID adGroupId, UUID storeId, String text) {
        return SearchTermEntity.builder()
                .id(id)
                .campaignId(campaignId)
                .adGroupId(adGroupId)
                .storeId(storeId)
                .searchTerm(text)
                .build();
    }

    private static CampaignEntity campaign(UUID id, UUID storeId) {
        return CampaignEntity.builder()
                .id(id)
                .storeId(storeId)
                .name("campaign-" + id)
                .build();
    }

    private static AdGroupEntity adGroup(UUID id, UUID campaignId, UUID storeId) {
        return AdGroupEntity.builder()
                .id(id)
                .campaignId(campaignId)
                .storeId(storeId)
                .name("ad-group-" + id)
                .build();
    }

    private static SearchTermHarvestRequest request(String action, String targetCampaignId, String targetAdGroupId) {
        SearchTermHarvestRequest req = new SearchTermHarvestRequest();
        req.setHarvestAction(action);
        req.setTargetCampaignId(targetCampaignId);
        req.setTargetAdGroupId(targetAdGroupId);
        return req;
    }

    private static KeywordEntity captureInsertedKeyword(KeywordMapper keywordMapper) {
        ArgumentCaptor<KeywordEntity> captor = ArgumentCaptor.forClass(KeywordEntity.class);
        verify(keywordMapper).insert(captor.capture());
        return captor.getValue();
    }
}
