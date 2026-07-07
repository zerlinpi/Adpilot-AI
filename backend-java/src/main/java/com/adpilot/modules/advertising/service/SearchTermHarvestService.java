package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.vo.SearchTermVo;

/**
 * Search-term harvesting (Req 13). Derives the target Campaign and Ad_Group from
 * the Search_Term (Req 13.1), honours an in-scope override target (Req 13.2),
 * and applies the chosen {@link HarvestAction} so that:
 *
 * <ul>
 *   <li>{@code add_exact} / {@code add_phrase} create an enabled Keyword (Req 13.3/13.4);</li>
 *   <li>{@code add_negative} creates a Negative_Keyword and NEVER a positive Keyword (Req 13.5);</li>
 *   <li>{@code watchlist} records a watch only, creating no Keyword or Negative_Keyword (Req 13.6).</li>
 * </ul>
 *
 * An unrecognised action (Req 13.7) or an unresolvable target (Req 13.8) is rejected.
 */
public interface SearchTermHarvestService {

    /**
     * Harvest the Search_Term identified by {@code searchTermId} according to the
     * request's {@link HarvestAction} and (optional) override target.
     *
     * @param searchTermId the Search_Term to harvest
     * @param request      the harvest action and optional override target/match/bid
     * @param userId       the acting user id (for audit columns), may be {@code null}
     * @return the updated Search_Term
     */
    SearchTermVo harvest(String searchTermId, SearchTermHarvestRequest request, String userId);
}
