package com.adpilot.modules.approval.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a governed action that may require approval before it executes.
 *
 * <p>Read by {@link com.adpilot.modules.approval.aspect.ApprovalAspect}, which,
 * before the method runs, checks whether an enabled
 * {@code approval_policies} row exists for the caller's organization matching
 * this {@link #module()} and {@link #action()} and requiring approval. When one
 * does, the aspect persists an {@code approval_request} and short-circuits the
 * method into a pending state instead of executing it; otherwise the method
 * executes immediately (Req 12.1.1).
 *
 * <p>Mirrors {@code @RequirePermission} / {@code PermissionAspect}. Apply to
 * controller or service methods, for example:
 * <pre>{@code
 * @RequiresApproval(module = "advertising", action = "bid_change")
 * public BidChangeResult applyBidChange(...) { ... }
 * }</pre>
 *
 * <p>When the action is gated, the annotated method returns {@code null} to its
 * caller; downstream routing and sequencing are handled by the ApprovalService
 * (task 24.2).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresApproval {

    /**
     * The governed module (for example {@code "advertising"}). Matched against
     * {@code approval_policies.module}.
     *
     * @return the module identifier
     */
    String module();

    /**
     * The governed action type (for example {@code "bid_change"}). Matched
     * against {@code approval_policies.action_type}.
     *
     * @return the action type identifier
     */
    String action();
}
