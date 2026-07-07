package com.adpilot.modules.advertising.operation.alert;

import java.util.UUID;

/**
 * Raises and resolves write-back failure alerts for a Store + Write_Connector (Req 51.7), producing
 * operator notifications through the dedupe/lifecycle contract of Requirement 23.2/23.3.
 *
 * <p>The OutboxWorker / StatusPoller / callback path reports every terminal write-back outcome
 * through {@link #recordOutcome(UUID, String, boolean)}. The alerting component maintains a rolling
 * view of recent attempts per Store + Write_Connector, evaluates the configured thresholds via
 * {@link WriteBackFailureEvaluator}, and:</p>
 *
 * <ul>
 *   <li><b>raises</b> a notification when the alert condition holds and no open notification already
 *       shares its dedupe key (Req 23.2) — a re-trip while the alert is still open does not create a
 *       second notification;</li>
 *   <li><b>closes</b> the stale notification when the condition is resolved (the failure rate drops
 *       below threshold and the consecutive-failure streak is broken), per Req 23.3.</li>
 * </ul>
 *
 * <p>All thresholds are configurable (Req 51.11).</p>
 */
public interface WriteBackAlerting {

    /**
     * Record a terminal write-back outcome for a Store + Write_Connector and reconcile the alert
     * state: raise a new notification if the threshold is now breached and none is open, or close the
     * open one if the condition has resolved.
     *
     * @param storeId   the Store the Operation belongs to; must not be {@code null}
     * @param connector the Write_Connector (platform) key; must not be {@code null}/blank
     * @param success   {@code true} iff the write-back succeeded (Operation reached {@code effective})
     * @return the decision computed after recording this outcome
     */
    WriteBackAlertDecision recordOutcome(UUID storeId, String connector, boolean success);

    /**
     * Evaluate the current alert decision for a Store + Write_Connector without recording a new
     * outcome. Returns a non-alerting decision when nothing has been observed yet.
     */
    WriteBackAlertDecision currentDecision(UUID storeId, String connector);
}
