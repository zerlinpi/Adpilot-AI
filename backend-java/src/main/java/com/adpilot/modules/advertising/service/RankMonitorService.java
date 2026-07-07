package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.RankMonitorCreateRequest;
import com.adpilot.modules.advertising.vo.RankMonitorQuotaVo;
import com.adpilot.modules.advertising.vo.RankMonitorTaskVo;

import java.util.List;

/**
 * Rank Monitoring (Req 28). Add quota-checked rank-monitor tasks, list a
 * store's monitored keywords with their latest organic + ad ranks, and report
 * the consumed/total monitoring quota. The admission rule is the pure
 * {@code RankQuota} helper: a task is permitted iff {@code consumed < total}
 * (Req 28.4).
 */
public interface RankMonitorService {

    /**
     * Add a rank-monitor task for a keyword (Req 28.1). Rejects with a
     * {@code BusinessException} when the store's monitoring quota is exhausted
     * (Req 28.4).
     */
    RankMonitorTaskVo createTask(RankMonitorCreateRequest request);

    /** List rank-monitor tasks for a store with their latest ranks (Req 28.2). */
    List<RankMonitorTaskVo> listTasks(String storeId);

    /** Report the consumed/total monitoring quota for a store (Req 28.3). */
    RankMonitorQuotaVo getQuota(String storeId);
}
