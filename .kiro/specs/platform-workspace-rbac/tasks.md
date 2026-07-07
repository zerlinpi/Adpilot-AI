# Implementation Plan: Platform Workspace RBAC

## Overview

This plan extends the existing `com.adpilot.common.security` RBAC primitives with two new dimensions (Store_Group_Scope and Platform_Access) and layers Store Groups, merged connection wizards, full Google Ads capability (read, manual write, AI hosting), AI-assisted customer tickets, and a four-block navigation rework onto the current Spring Boot 3.2.5 / MyBatis-Plus backend and React/TypeScript frontend.

The work proceeds bottom-up: schema and core RBAC types first, then the shared data-scope and platform-access enforcement (the security boundary of Requirement 16), then the feature modules that consume those guards, then the frontend shell and button audit. Property-based tests use **jqwik** (backend) and **fast-check** (frontend), mirroring the existing `TableViewIsolationPropertyTest` pattern, and the data-isolation invariants (Properties 1–4) are prioritized.

Each property test must be tagged with a comment:
`Feature: platform-workspace-rbac, Property {number}: {property_text}` and run a minimum of 100 tries (200 for isolation properties, matching the existing convention).

## Tasks

- [x] 1. Schema and migration foundations
  - [x] 1.1 Add Store_Group and Platform_Access schema to `backend-java/db/schema.sql`
    - Add `store_groups` table (id, org_id, name, platform_family CHECK in ('amazon','independent_site'), is_default, created_by, timestamps, UNIQUE(org_id, platform_family, name))
    - Add `account_platform_access` table (id, user_id, platform_family CHECK in four families, UNIQUE(user_id, platform_family))
    - Add `stores.store_group_id CHAR(36)` and `stores.platform_family VARCHAR(20)` columns (retain legacy `store_group` label)
    - Add `data_scopes.store_group_ids JSON` column and document the `assigned_store_group` scope_type value
    - Add `customer_tickets.store_group_id`, `customer_tickets.ai_drafted`, `customer_tickets.ai_classification` columns
    - Do not remove existing `data_scopes`, `user_stores`, or `permissions` tables
    - _Requirements: 18.1, 18.3, 10.1, 12.1, 9.2_

  - [x] 1.2 Implement idempotent migration backfill
    - Seed per-family default `store_groups` rows (amazon + independent_site) keyed by unique constraint
    - Backfill `stores.store_group_id` and `stores.platform_family` from the legacy `store_group` label + platform, defaulting to the family default group when absent
    - Grant a defined default Platform_Access to accounts with no `account_platform_access` rows
    - Use `INSERT ... ON DUPLICATE KEY` / existence-guarded updates so re-running yields identical assignments and grants
    - _Requirements: 18.2, 18.5, 18.6, 10.7_

  - [x] 1.3 Write property test for migration determinism and idempotency
    - **Property 24: Migration is deterministic and idempotent**
    - **Validates: Requirements 18.6, 18.2, 18.5**

  - [x] 1.4 Write property test for preserved assigned-store scope
    - **Property 25: Backward-compatible assigned-store scope is preserved**
    - **Validates: Requirements 18.4**

- [x] 2. Core RBAC model types and entities
  - [x] 2.1 Add PlatformFamily enum, Store_Group and Platform_Access entities + mappers
    - Add `PlatformFamily` enum (`amazon`, `independent_site`, `logistics`, `finance`)
    - Add `StoreGroupEntity` + `StoreGroupMapper`, `AccountPlatformAccessEntity` + `AccountPlatformAccessMapper`
    - Add `StoreGroupVo` and command/DTO types for create/rename/assign
    - _Requirements: 10.1, 12.1, 11.4_

  - [x] 2.2 Extend EffectiveScope, ScopeType, and ScopeTarget for store groups
    - Add `Set<UUID> storeGroupIds` to `EffectiveScope`; keep `superAdmin()` unrestricted
    - Add `ScopeType.ASSIGNED_STORE_GROUP` at the assigned-store/product precedence tier
    - Add `storeGroupIdColumn` (or store→group resolution) to `ScopeTarget` so list queries and single-record guards both filter by store group
    - _Requirements: 13.1, 13.6, 18.3_

- [x] 3. Store Group service
  - [x] 3.1 Implement StoreGroupService
    - Implement `create` (name + platform-family validation), `rename`, `assignStore` (platform-family match), `listByFamily`, `defaultGroupFor`
    - Persist new association and apply it to subsequent data-scope evaluations
    - Throw validation `BusinessException` on name conflict/limit and platform-family mismatch
    - _Requirements: 10.3, 10.4, 10.5, 10.6, 11.1, 11.2, 11.3_

  - [x] 3.2 Write property test for store-group name validation
    - **Property 11: Store-group name validation**
    - **Validates: Requirements 10.1, 10.4**

  - [x] 3.3 Write property test for platform-family match on assignment
    - **Property 10: Store-group platform-family match on assignment**
    - **Validates: Requirements 10.6, 11.4**

  - [x] 3.4 Write property test for single-group resolution of every store
    - **Property 9: Every store resolves to exactly one Store_Group of its platform family**
    - **Validates: Requirements 10.2, 10.7, 4.5, 5.4**

  - [x] 3.5 Write property test for runtime usability of new Amazon groups
    - **Property 12: Newly created Amazon group is immediately usable**
    - **Validates: Requirements 11.1, 11.2, 11.3**

  - [x] 3.6 Write unit tests for StoreGroupService create and reassignment
    - Test create success returns generated id (10.3) and reassignment applies to scope (10.5)
    - _Requirements: 10.3, 10.5_

- [x] 4. Platform Access dimension
  - [x] 4.1 Implement PlatformAccessService, @RequirePlatform, and PlatformAccessAspect
    - Implement `PlatformAccessService.accessibleFamilies` / `hasAccess`; super-admin returns all four families
    - Add `@RequirePlatform(PlatformFamily)` annotation and `PlatformAccessAspect` mirroring `@RequirePermission`/`PermissionAspect`, throwing the shared 403 `BusinessException` before the method body executes
    - _Requirements: 12.3, 12.4, 15.4, 16.3_

  - [x] 4.2 Write property test for cross-platform rejection
    - **Property 3: Cross-platform rejection**
    - **Validates: Requirements 16.3, 12.3, 12.4**

- [x] 5. Store-Group data-scope enforcement (security boundary)
  - [x] 5.1 Extend DataScopeServiceImpl with store-group scope
    - Extend `resolve()` to union store-group ids across the account's roles into `EffectiveScope.storeGroupIds`
    - Extend `applyScope` to add a predicate filtering the queried entity's store to those whose `store_group_id` is in scope, through the shared layer
    - Implement a single shared decision function used by both `assertCanRead` and `assertCanWrite` so read and write reach the identical allow/deny outcome; throw 403 on cross-group single-record access
    - Preserve existing `assigned_store` / `user_stores` honoring alongside the new dimension
    - _Requirements: 13.2, 13.3, 13.4, 13.6, 13.7, 16.1, 16.2, 16.4, 16.5, 18.4_

  - [x] 5.2 Write property test for store-group read isolation
    - **Property 1: Store-group read isolation**
    - **Validates: Requirements 16.1, 13.2, 9.7**

  - [x] 5.3 Write property test for store-group write isolation
    - **Property 2: Store-group write isolation**
    - **Validates: Requirements 16.2, 13.3, 13.4**

  - [x] 5.4 Write property test for read/write isolation consistency
    - **Property 4: Read/write isolation consistency**
    - **Validates: Requirements 16.4**

  - [x] 5.5 Write property test for store-group scope union across roles
    - **Property 7: Store-group scope is the union across roles**
    - **Validates: Requirements 13.7**

  - [x] 5.6 Write property test for super-administrator bypass
    - **Property 5: Super-administrator bypass**
    - **Validates: Requirements 15.1, 15.2, 15.4**

  - [x] 5.7 Write property test for the functional-permission gate
    - **Property 6: Functional-permission gate** (including distinct Amazon vs independent-site advertising permissions)
    - **Validates: Requirements 14.4, 14.5, 14.2, 7.5**

  - [x] 5.8 Write property test for RBAC dimension independence
    - **Property 8: RBAC dimensions are independent**
    - **Validates: Requirements 14.7, 14.3**

- [x] 6. Checkpoint - RBAC core complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Connection wizards
  - [x] 7.1 Wire Amazon_Connection_Wizard to store-group assignment
    - On OAuth success, create/update `platform_connections`, bind store, and assign the store to a selected/created Amazon Store_Group in one flow
    - On failure/cancel leave any existing connection unchanged and surface the failure reason
    - _Requirements: 4.2, 4.3, 4.4, 4.5_

  - [x] 7.2 Implement Independent_Site_Connection_Wizard backend
    - Accept Shopify/WooCommerce/TikTok credentials, create `PlatformConnectionEntity`, and bind store in one flow; assign to the independent-site Store_Group system
    - Associate Google Ads binding with the selected independent-site store rather than a standalone connection
    - Leave existing connection unchanged and surface the rejection reason on credential failure
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

  - [x] 7.3 Write integration tests for both connection wizards
    - Amazon one-step OAuth + bind + group assignment against a mocked OAuth endpoint (4.2, 4.3)
    - Independent-site credential submission + bind + group assignment (5.2)
    - _Requirements: 4.2, 4.3, 5.2_

- [x] 8. Google Ads read
  - [x] 8.1 Implement GoogleAdsReadService
    - Retrieve campaigns and performance reports through the existing `GoogleAdsConnector`; return name/status/budget/metrics and date-ranged performance
    - On retrieval failure return an error indicator without mutating prior data; signal connect-prompt state when no active connection exists
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [x] 8.2 Write unit tests for Google Ads read service
    - Test display of campaigns/reports (6.3, 6.4), connect prompt (6.6), and error/retry path (6.5)
    - _Requirements: 6.3, 6.4, 6.5, 6.6_

- [x] 9. Google Ads manual operations
  - [x] 9.1 Implement Google Ads manual write endpoints through the Operation pipeline
    - Campaign create → `platform_mutation` Operation with `OperationSource.CREATION`; bid/budget/status change → `OperationSource.MANUAL`, both routed through `OperationService` → `operation_outbox` → `GoogleAdsWriteConnector`
    - Advance Sync_States and record platform response on accept; record failure reason and leave internal record unchanged on reject
    - Require the independent-site advertising functional permission; return 403 with no Operation when absent
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 9.2 Write property test for manual Google Ads Operation creation
    - **Property 13: Manual Google Ads action produces a platform-mutation Operation**
    - **Validates: Requirements 7.1, 7.2**

  - [x] 9.3 Write unit tests for permission rejection and pending overlay
    - Test 403 with no Operation on missing permission (7.5) and pending-change display through `PendingOverlayService` (7.6)
    - _Requirements: 7.5, 7.6_

- [x] 10. Google Ads AI hosting
  - [x] 10.1 Implement GoogleAdsHostingEngine and wire to Optimization_Coordinator
    - Produce `CandidateDecision` objects (campaign-create, bid/budget/status) from performance data over the resolved personality policy lookback window; emit to `OptimizationCoordinator` (no direct Operation creation)
    - Accepted candidates become `platform_mutation` Operations with `OperationSource.AI_HOSTING`, recording before/after values, decision snapshot, and explanation; reuse `ExecutionModeResolver`, approval routing, `OutboxWorker`, `AttributionWorker`, and `DataQualityGate`
    - Clamp every proposed change within the resolved Safety_Boundary; skip campaigns failing the data-quality check with a recorded reason
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

  - [x] 10.2 Write property test for attributed AI-hosting Operation
    - **Property 14: AI-hosting Google Ads decision produces an attributed platform-mutation Operation**
    - **Validates: Requirements 8.3**

  - [x] 10.3 Write property test for execution-mode gating
    - **Property 15: AI hosting respects execution mode**
    - **Validates: Requirements 8.4**

  - [x] 10.4 Write property test for safety-boundary clamping
    - **Property 16: AI hosting clamps to the safety boundary**
    - **Validates: Requirements 8.5**

  - [x] 10.5 Write property test for data-quality skip
    - **Property 17: AI hosting skips on data-quality failure**
    - **Validates: Requirements 8.8**

- [x] 11. Checkpoint - Google Ads pipeline complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Customer tickets under Amazon with AI assistance
  - [x] 12.1 Implement ticket store-group association and TicketAiAssistantService
    - On buyer-message → ticket conversion, set the ticket's `store_group_id` from the originating store's group
    - `assist(ticketId)` returns draft reply, suggested classification, and suggested action as proposals; nothing is sent/applied until explicit operator confirmation
    - On confirmation apply the action and record AI provenance in the audit trail; on generation failure leave the ticket unchanged and surface the reason
    - Enforce that tickets outside the operator's Store_Group_Scope are not displayed (via the shared data-scope layer)
    - _Requirements: 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

  - [x] 12.2 Write property test for ticket store-group inheritance
    - **Property 18: Customer ticket inherits its store's group**
    - **Validates: Requirements 9.2**

  - [x] 12.3 Write property test for explicit AI confirmation
    - **Property 19: AI ticket outputs require explicit confirmation**
    - **Validates: Requirements 9.4**

- [x] 13. Frontend navigation shell and permission context
  - [x] 13.1 Restructure Layout into a four-block, two-level navigation via navConfig
    - Add `navConfig.ts` encoding the four blocks (亚马逊, 独立站, 物流, 财务统计) and their ordered Nav_Items with route, permission, and platform family
    - Restructure `Layout.tsx` to render exactly four blocks in fixed order with expand/collapse (multiple open allowed), active-item indication, and Account_Area utilities (系统设置, 飞书机器人, 审计回滚, CSV导入)
    - Persist/restore expand/collapse state to `localStorage`; do not render a Nav_Item whose route does not resolve
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.4_

  - [x] 13.2 Extend PermissionContext with platformAccess and storeGroupScope
    - Fetch `{ permissions, platformAccess, storeGroupScope }` from `/api/auth/me`; gate block/item/Account_Area visibility on Platform_Access + functional permission; super-admin shows all
    - Re-render navigation on the next permission fetch when dimensions change, without re-login
    - _Requirements: 1.7, 3.2, 3.3, 12.2, 12.5, 14.6, 15.3_

  - [x] 13.3 Filter the store switcher by Store_Group_Scope
    - Display only stores whose Store_Group is within the account's Store_Group_Scope
    - _Requirements: 13.5_

  - [x] 13.4 Write property test for navigation block visibility
    - **Property 20: Navigation block visibility**
    - **Validates: Requirements 1.7, 3.2, 3.3, 12.2, 15.3**

  - [x] 13.5 Write property test for expand/collapse state round-trip
    - **Property 21: Navigation expand/collapse state round-trips through persistence**
    - **Validates: Requirements 1.6**

  - [x] 13.6 Write property test for Nav_Item route/family consistency
    - **Property 22: Every rendered Nav_Item resolves to a route and matches its block's family**
    - **Validates: Requirements 2.5, 2.6**

- [x] 14. Frontend Google Ads and customer-ticket UI wiring
  - [x] 14.1 Build the Google Ads module UI under 独立站
    - Wire 连接/列表/报告/创建/调价/AI托管 views to the read service, manual-operation endpoints, and hosting controls; show connect prompt and pending overlay; show error + retry on failure
    - _Requirements: 6.3, 6.4, 6.6, 7.6_

  - [x] 14.2 Build the customer-ticket UI under 亚马逊 with AI assist
    - Place 客服工单 under the Amazon block; present AI draft/classification/action as proposals requiring explicit confirmation before send/apply
    - _Requirements: 9.1, 9.3, 9.4_

- [x] 15. Frontend API client error surfacing and button audit
  - [x] 15.1 Ensure the shared API client surfaces every error response
    - Modify `frontend/src/app/lib/api.ts` so any error HTTP status surfaces an error indication to the operator rather than resolving silently
    - _Requirements: 17.4, 12.3_

  - [x] 15.2 Write property test for API-client error surfacing
    - **Property 23: API client surfaces every error response**
    - **Validates: Requirements 17.4**

  - [x] 15.3 Enumerate and repair broken controls (button audit)
    - Enumerate every interactive control reachable through the four blocks and the Account_Area, recording broken controls (page, control, observed failure)
    - Repair each enumerated broken control so it invokes the correct endpoint and surfaces success/error feedback
    - _Requirements: 17.1, 17.2, 17.3, 17.5_

- [x] 16. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP, though the isolation properties (1–4, tasks 5.2–5.4) are the security boundary mandated by Requirement 16 and are strongly recommended.
- Each task references specific requirements clauses for traceability.
- Property tests use jqwik (backend) and fast-check (frontend), each tagged with `Feature: platform-workspace-rbac, Property {n}` and running ≥100 tries (200 for isolation), reusing the mock-mapper modeling pattern from `TableViewIsolationPropertyTest`.
- All new schema lives in `backend-java/db/schema.sql` (no Flyway, no runtime DDL); the migration is idempotent.
- Checkpoints ensure incremental validation at the end of the RBAC core, the Google Ads pipeline, and the full feature.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "13.1", "15.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "1.4", "2.2", "13.5", "15.2"] },
    { "id": 3, "tasks": ["3.1", "4.1"] },
    { "id": 4, "tasks": ["3.2", "3.3", "3.4", "3.5", "3.6", "4.2", "5.1"] },
    { "id": 5, "tasks": ["5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "5.8", "7.1", "7.2"] },
    { "id": 6, "tasks": ["7.3", "8.1", "9.1", "13.2"] },
    { "id": 7, "tasks": ["8.2", "9.2", "9.3", "10.1", "13.3"] },
    { "id": 8, "tasks": ["10.2", "10.3", "10.4", "10.5", "12.1", "14.1", "14.2"] },
    { "id": 9, "tasks": ["12.2", "12.3", "13.4", "13.6"] },
    { "id": 10, "tasks": ["15.3"] }
  ]
}
```
