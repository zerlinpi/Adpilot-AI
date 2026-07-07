package com.adpilot.modules.approval.service.impl;

import com.adpilot.modules.approval.service.ApprovalWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically expires and cancels approval requests left unapproved past their
 * expiration (Req 12.1.7).
 *
 * <p>Scheduling is already enabled application-wide via
 * {@code com.adpilot.modules.scheduler.config.SchedulerConfig}
 * ({@code @EnableScheduling}). The sweep delegates to
 * {@link ApprovalWorkflowService#expireOverdue()}; the interval is configurable
 * via {@code adpilot.approval.expiry-sweep-ms} (default 60s).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalExpirationSweeper {

    private final ApprovalWorkflowService approvalWorkflowService;

    @Scheduled(fixedDelayString = "${adpilot.approval.expiry-sweep-ms:60000}")
    public void sweep() {
        try {
            approvalWorkflowService.expireOverdue();
        } catch (Exception ex) {
            log.warn("Approval expiration sweep failed", ex);
        }
    }
}
