package com.adpilot.modules.advertising.service.impl;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.advertising.service.HarvestAction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the object produced by a search-term harvest
 * ({@link SearchTermHarvestServiceImpl#harvest}).
 *
 * <p>Feature: advertising-workspace-rework, Property 31: Harvest action determines
 * the produced object.
 *
 * <p>Validates: Requirements 13.3, 13.4, 13.5, 13.6, 13.7.
 *
 * <p>Property 31 (transcribed from the design's Correctness Properties section):
 * <em>For any Search_Term and Harvest_Action, harvesting produces exactly: an
 * enabled exact-match Keyword for {@code add_exact}; an enabled phrase-match
 * Keyword for {@code add_phrase}; a Negative_Keyword (and never a positive
 * Keyword) for {@code add_negative}; a watch record (and neither Keyword nor
 * Negative_Keyword) for {@code watchlist}; and an invalid action is rejected with
 * an error naming it.</em>
 *
 * <p>The service runs against mocked mappers and a mocked {@link DataScopeService}
 * (no override target is supplied, so the derived target is resolved from the term
 * and no scope check fires). The target campaign and ad group always resolve, so
 * the action is the only dimension that decides which object is produced. The raw
 * action string spans the four valid actions (in canonical, padded, and mixed-case
 * forms to exercise the trim/case-insensitive parse) and a wide range of invalid
 * values; the search-term text varies freely to prove the routing decision depends
 * solely on the chosen action.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 31: Harvest action determines the produced object")
class HarvestActionOutputPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /**
     * Feature: advertising-workspace-rework, Property 31: Harvest action determines the produced object.
     *
     * <p>Validates: Requirements 13.3, 13.4, 13.5, 13.6, 13.7.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 31: the harvest action alone determines the produced object, and an invalid action is rejected by name")
    void harvestActionDeterminesProducedObject(
            @ForAll("rawActions") String rawAction,
            @ForAll("searchTermTexts") String termText) {

        SecurityContextHolder.clearContext();

        // --- Collaborators (mocked) -----------------------------------------------------------
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        NegativeKeywordMapper negativeKeywordMapper = mock(NegativeKeywordMapper.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);

        // --- A fully resolvable Search_Term whose target campaign and ad group both exist -----
        UUID searchTermId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID adGroupId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        SearchTermEntity searchTerm = SearchTermEntity.builder()
                .id(searchTermId)
                .campaignId(campaignId)
                .adGroupId(adGroupId)
                .storeId(storeId)
                .searchTerm(termText)
                .harvested(false)
                .harvestingStatus("candidate")
                .build();
        CampaignEntity campaign = CampaignEntity.builder()
                .id(campaignId).storeId(storeId).name("campaign").build();
        AdGroupEntity adGroup = AdGroupEntity.builder()
                .id(adGroupId).campaignId(campaignId).storeId(storeId).name("group")
                .defaultBid(new BigDecimal("0.75")).build();

        when(searchTermMapper.selectById(searchTermId)).thenReturn(searchTerm);
        when(campaignMapper.selectById(campaignId)).thenReturn(campaign);
        when(adGroupMapper.selectById(adGroupId)).thenReturn(adGroup);

        SearchTermHarvestRequest request = new SearchTermHarvestRequest();
        request.setHarvestAction(rawAction);

        SearchTermHarvestServiceImpl service = new SearchTermHarvestServiceImpl(
                searchTermMapper, campaignMapper, adGroupMapper,
                keywordMapper, negativeKeywordMapper, dataScopeService);

        Optional<HarvestAction> parsed = HarvestAction.fromValue(rawAction);

        if (parsed.isEmpty()) {
            // Req 13.7 — an action outside the four supported values is rejected, and the error
            // names the invalid action. No targeting object of any kind is produced.
            assertThatThrownBy(() -> service.harvest(searchTermId.toString(), request, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(rawAction);
            verify(keywordMapper, never()).insert(any());
            verify(negativeKeywordMapper, never()).insert(any());
            return;
        }

        service.harvest(searchTermId.toString(), request, null);

        switch (parsed.get()) {
            case ADD_EXACT -> {
                // Req 13.3 — exactly one enabled exact-match Keyword, no Negative_Keyword.
                KeywordEntity keyword = captureInsertedKeyword(keywordMapper);
                assertThat(keyword.getMatchType()).isEqualTo("exact");
                assertThat(keyword.getStatus()).isEqualTo("enabled");
                assertThat(keyword.getAdGroupId()).isEqualTo(adGroupId);
                verify(negativeKeywordMapper, never()).insert(any());
            }
            case ADD_PHRASE -> {
                // Req 13.4 — exactly one enabled phrase-match Keyword, no Negative_Keyword.
                KeywordEntity keyword = captureInsertedKeyword(keywordMapper);
                assertThat(keyword.getMatchType()).isEqualTo("phrase");
                assertThat(keyword.getStatus()).isEqualTo("enabled");
                assertThat(keyword.getAdGroupId()).isEqualTo(adGroupId);
                verify(negativeKeywordMapper, never()).insert(any());
            }
            case ADD_NEGATIVE -> {
                // Req 13.5 — a Negative_Keyword is produced and NEVER a positive Keyword.
                verify(negativeKeywordMapper).insert(any(NegativeKeywordEntity.class));
                verify(keywordMapper, never()).insert(any());
            }
            case WATCHLIST -> {
                // Req 13.6 — a watch record only: neither Keyword nor Negative_Keyword.
                verify(keywordMapper, never()).insert(any());
                verify(negativeKeywordMapper, never()).insert(any());
                assertThat(searchTerm.getHarvestingStatus()).isEqualTo(HarvestAction.WATCHLIST.value());
                assertThat(searchTerm.getHarvested()).isFalse();
            }
        }
    }

    private static KeywordEntity captureInsertedKeyword(KeywordMapper keywordMapper) {
        ArgumentCaptor<KeywordEntity> captor = ArgumentCaptor.forClass(KeywordEntity.class);
        verify(keywordMapper).insert(captor.capture());
        return captor.getValue();
    }

    // --- generators -----------------------------------------------------------------------------

    /**
     * Raw harvest-action strings: the four valid actions (canonical, whitespace-padded, and
     * mixed-case, all of which {@link HarvestAction#fromValue} accepts) mixed with a broad range of
     * invalid values that {@code fromValue} rejects.
     */
    @Provide
    Arbitrary<String> rawActions() {
        Arbitrary<String> valid = Arbitraries.of(
                "add_exact", "add_phrase", "add_negative", "watchlist",
                "ADD_EXACT", "Add_Phrase", "  add_negative  ", " watchlist");
        Arbitrary<String> invalid = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('_', '-', ' ', 'A', 'Z', '1', '9')
                .ofMinLength(0)
                .ofMaxLength(24)
                .filter(s -> HarvestAction.fromValue(s).isEmpty());
        return Arbitraries.oneOf(valid, invalid);
    }

    /** Non-blank search-term text within the 500-char column bound. */
    @Provide
    Arbitrary<String> searchTermTexts() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '0', '9')
                .ofMinLength(1)
                .ofMaxLength(80)
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }
}
