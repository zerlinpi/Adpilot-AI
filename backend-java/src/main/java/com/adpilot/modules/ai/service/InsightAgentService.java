package com.adpilot.modules.ai.service;

import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.adpilot.modules.ai.vo.SavedInsightVo;

import java.util.List;

/**
 * Insight Agent conversational analysis (Req 24). Uses the configured
 * {@code AiClient} when AI is enabled, otherwise falls back to a deterministic
 * stub that summarizes the store's stored metrics.
 *
 * <p>Every generated insight is persisted (item 16) and retained until the
 * operator manually deletes it, so the saved-insights history survives reloads.</p>
 */
public interface InsightAgentService {

    /** Process a store-scoped query and return insights + recommended actions (Req 24.1). */
    InsightResultVo query(InsightQueryRequest request);

    /** Suggested prompts shown when the page loads (Req 24.2). */
    List<String> suggestions();

    /** List saved insights for a store, newest first (item 16). */
    List<SavedInsightVo> listSavedInsights(String storeId);

    /** Manually delete a saved insight by id (item 16). */
    void deleteSavedInsight(String id);
}
