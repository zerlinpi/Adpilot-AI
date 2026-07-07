package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.AiNotificationConfigRequest;
import com.adpilot.modules.advertising.vo.AiNotificationConfigVo;
import com.adpilot.modules.advertising.vo.AiNotificationOverviewVo;
import com.adpilot.modules.advertising.vo.AiNotificationVo;

/**
 * AI Notifications work-item workflow (Req 23). Notifications are raised by the
 * AI advertising module into four categories, each with a pending(待处理) and a
 * closed(已结束) list. Operators apply one-click optimizations or confirm/reject
 * AI target corrections, which close the item via the pure
 * {@link com.adpilot.modules.advertising.support.AiNotificationStateMachine}.
 */
public interface AiNotificationService {

    /**
     * Build the four-category overview for the active store with per-category
     * pending/closed counts and pending/closed item lists (Req 23.1, 23.2, 23.6).
     */
    AiNotificationOverviewVo getOverview(String storeId);

    /** Apply a one-click optimization on a pending item, closing it (Req 23.3). */
    AiNotificationVo apply(String id);

    /** Confirm an AI target correction, applying the change and closing it (Req 23.4). */
    AiNotificationVo confirm(String id);

    /** Reject an AI target correction, discarding the change and closing it (Req 23.4). */
    AiNotificationVo reject(String id);

    /**
     * Push a notification to the store's bound Feishu chat(s) via the existing
     * Feishu integration (item 19). No-ops gracefully when no Feishu chat is
     * bound. Returns the notification unchanged.
     */
    AiNotificationVo pushToFeishu(String id);

    /** Get the per-store notification configuration (Req 23.5). */
    AiNotificationConfigVo getConfig(String storeId);

    /** Upsert the per-store notification configuration (Req 23.5). */
    AiNotificationConfigVo updateConfig(AiNotificationConfigRequest request);
}
