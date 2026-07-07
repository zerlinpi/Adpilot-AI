# Implementation Plan: Core Platform Completion

## Overview

This plan turns AdPilot AI from a CRUD shell into a live, automated, access-controlled platform by implementing the design incrementally, sequenced by phase P0 → P3. Each task builds on prior tasks and ends by wiring the new code into the running system.

Implementation languages follow the design: **Java (Spring Boot, MyBatis-Plus, Flyway, Redis, JWT)** for the backend under `backend-java`, and **React/TypeScript** for the frontend under `frontend`.

Property-based tests use **jqwik** (Java) and **fast-check** (TypeScript), each running a minimum of 100 iterations and tagged `Feature: core-platform-completion, Property {n}: {text}`. Property test sub-tasks are marked optional with `*`; each maps to exactly one of the 29 correctness properties and the requirement clauses it validates.

---

## Tasks

### Phase P0 — Real Data Flow and Access Control

- [x] 1. Establish sync foundation schema and core value objects
  - Create Flyway migration `V3__sync_foundation.sql` adding `sync_watermarks` and `sync_record_errors` tables per the design's data model
  - Add MyBatis-Plus entities and mappers for `sync_watermarks` and `sync_record_errors`
  - Define core sync value objects: `ExternalRecord`, `ExternalPage`, `PageCursor`, `MappedRecord`, `UpsertResult`, `ValidationResult`, `FieldError`, `ConnectionContext`, `SyncContext`
  - _Requirements: 1.1.6, 1.1.7, 1.1.8, 1.4.1_

- [x] 2. Implement the connector SPI and record mapping
  - [x] 2.1 Define `PlatformDataConnector` interface and refactor existing `PlatformConnector` toward it
    - Add `platform()`, `pullOrders`, `pullProducts` (default `pullInventory`, `pullAdReports`, `discoverStores`)
    - Preserve existing credential-test behavior; decrypt credentials on demand via `CryptoUtil`, never log secrets
    - _Requirements: 1.1.1_

  - [x] 2.2 Implement WooCommerce and Shopify connectors for orders and products
    - Implement `pullOrders`/`pullProducts` returning normalized `ExternalRecord` streams with change timestamps and status
    - Support incremental (`since`) and full pulls with paging cursors
    - _Requirements: 1.1.1, 1.1.6, 1.1.7_

  - [x] 2.3 Implement `RecordMapper` mapping external records to internal entity fields and store association
    - Map external order/product records to `channel_orders`/`channel_products` fields with correct types
    - Associate each mapped record with the store owning the originating connection
    - _Requirements: 1.1.3, 1.1.4_

  - [x] 2.4 Write property test for record mapping
    - **Property 1: External-record mapping populates internal fields and store association**
    - **Validates: Requirements 1.1.3, 1.1.4, 8.1.3**

- [x] 3. Implement watermark store and incremental selection
  - [x] 3.1 Implement `WatermarkStore` over `sync_watermarks` with `get` and `advance`
    - Resolve incremental window from the per-(store, entityType) watermark; full pull when absent or full resync requested
    - Advance watermark to the maximum processed change timestamp on success, never regressing
    - _Requirements: 1.1.6, 1.1.7, 1.1.8_

  - [x] 3.2 Write property test for incremental selection
    - **Property 2: Incremental retrieval selects only post-watermark records**
    - **Validates: Requirements 1.1.6, 1.1.7**

  - [x] 3.3 Write property test for watermark advancement
    - **Property 3: Successful sync advances the watermark monotonically to the latest processed record**
    - **Validates: Requirements 1.1.8**

- [x] 4. Implement idempotent upsert and data-quality validation
  - [x] 4.1 Implement `UpsertService` keyed on `external_entity_mappings` unique key
    - Update existing internal record when external id maps; create record + mapping when unmapped
    - Mark internal records cancelled/inactive for platform-removed records instead of deleting
    - _Requirements: 1.2.1, 1.2.2, 1.2.3, 1.2.4_

  - [x] 4.2 Write property test for idempotent upsert
    - **Property 4: Upsert is idempotent and keyed on the external identifier**
    - **Validates: Requirements 1.2.1, 1.2.2, 1.2.3, 8.1.4**

  - [x] 4.3 Write property test for status-marking removed records
    - **Property 5: Externally removed records are status-marked, never physically deleted**
    - **Validates: Requirements 1.2.4**

  - [x] 4.4 Implement `DataQualityValidator` with per-entity required-field and type/format rules
    - Exclude invalid records from upsert and record one `sync_record_errors` entry per invalid record; continue processing
    - _Requirements: 1.4.1, 1.4.2, 1.4.3_

  - [x] 4.5 Write property test for validation and continue-on-error
    - **Property 6: Validation excludes invalid records, logs each, and processing continues with consistent counts**
    - **Validates: Requirements 1.3.2, 1.3.4, 1.4.1, 1.4.2, 1.4.3**

- [x] 5. Implement the sync job runner and endpoints
  - [x] 5.1 Implement `SyncJobRunner` lifecycle, single-flight, and orchestration
    - `startSync` creates a job; `execute` runs connector → mapper → validator → upsert → watermark
    - Record running/completed/failed status, start/completion times, processed/failed counts, and record-level logs
    - Mark job failed and record reason on rejected credentials
    - Enforce single running job per (store, entityType): reject or queue concurrent requests
    - _Requirements: 1.1.2, 1.1.5, 1.3.1, 1.3.2, 1.3.3, 1.3.4, 1.3.6_

  - [x] 5.2 Add sync controller endpoints and history query
    - `POST /api/stores/{storeId}/sync`, `GET /api/stores/{storeId}/sync-jobs` (most-recent-first), `GET /api/api-sync/jobs/{id}/logs`
    - Return results via `ApiResponse`/`PageResponse`
    - _Requirements: 1.1.2, 1.3.5_

  - [x] 5.3 Write property test for sync history ordering
    - **Property 7: Sync history is ordered most-recent-first**
    - **Validates: Requirements 1.3.5**

  - [x] 5.4 Write property test for single-flight concurrency
    - **Property 8: At most one running sync per store and entity type**
    - **Validates: Requirements 1.3.6**

  - [x] 5.5 Write unit tests for job lifecycle and on-demand creation
    - Job start/running/complete transitions; on-demand job creation
    - _Requirements: 1.3.1, 1.3.3, 1.1.2_

  - [x] 5.6 Write integration tests for WooCommerce/Shopify pulls and credential rejection
    - Pull orders/products against mock platform servers; rejected-credentials failure path
    - _Requirements: 1.1.1, 1.1.5_

- [x] 6. Checkpoint — sync engine
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Populate the JWT principal and audit infrastructure
  - [x] 7.1 Implement `UserContextService` loading permissions, orgId, departmentId with Redis cache + invalidation
    - Populate `CurrentUser` per request in `JwtAuthFilter`; cache permission set keyed by user id with short TTL
    - Expose `invalidate(userId)` invoked on role/permission change
    - _Requirements: 2.1.1, 3.1.5_

  - [x] 7.2 Implement `AuditService` writing to `audit_logs`
    - Record user identity and requested operation for permit and deny decisions
    - _Requirements: 2.1.5_

- [x] 8. Implement permission enforcement
  - [x] 8.1 Implement `@RequirePermission` annotation and `PermissionAspect`
    - Around advice uses existing `PermissionChecker`; super_admin bypass; throw 403 on missing permission; write audit entry in all cases
    - Ensure 401 returned for missing/invalid token on protected routes via `SecurityConfig`
    - _Requirements: 2.1.1, 2.1.2, 2.1.3, 2.1.4, 2.1.5_

  - [x] 8.2 Annotate existing controllers with required permissions
    - Apply `@RequirePermission` to operations across the existing modules that require a permission
    - _Requirements: 2.1.1, 2.1.2_

  - [x] 8.3 Write property test for authorization decisions and auditing
    - **Property 9: Authorization permits exactly authorized callers and audits every decision**
    - **Validates: Requirements 2.1.1, 2.1.2, 2.1.4, 2.1.5**

  - [x] 8.4 Write unit test for 401 on protected route without token
    - _Requirements: 2.1.3_

- [x] 9. Implement the shared data-scope query layer
  - [x] 9.1 Implement `DataScopeService` with effective-scope resolution and query/guard primitives
    - `resolve` computes effective scope by precedence all-company → department → assigned-store/assigned-product → own (union across roles)
    - `applyScope` produces a reusable `QueryWrapper` fragment; `assertCanRead`/`assertCanWrite` raise 403 for out-of-scope single records
    - Super-admin bypass; restrict assigned-store scope to `user_stores`; department and own dimensions supported
    - _Requirements: 2.2.1, 2.2.2, 2.2.3, 2.2.4, 2.2.5, 7.1.1, 7.1.2, 7.1.3, 7.1.4, 7.1.5_

  - [x] 9.2 Wire `DataScopeService` into scoped module queries and writes
    - Apply scope filtering to list queries and single-record read/write guards across data-scoped modules
    - _Requirements: 2.2.1, 2.2.2, 2.2.3, 7.1.5_

  - [x] 9.3 Write property test for scope filtering across all dimensions
    - **Property 10: Data-scope filtering restricts reads and writes to permitted records across every scope dimension**
    - **Validates: Requirements 2.2.1, 2.2.3, 2.2.4, 2.2.5, 7.1.1, 7.1.2, 7.1.3, 7.1.5, 10.1.5**

  - [x] 9.4 Write property test for effective-scope resolution
    - **Property 11: Effective data scope is the broadest applicable across a user's roles**
    - **Validates: Requirements 7.1.4**

- [x] 10. Checkpoint — backend access control
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Implement frontend permission-driven UX
  - [x] 11.1 Implement `PermissionContext` and `can(permission)` from `GET /api/auth/me`
    - Load permission set after login; re-fetch on navigation to refresh without logout
    - _Requirements: 3.1.3, 3.1.5_

  - [x] 11.2 Implement `<RequirePermission>` control wrapper and permission-aware navigation
    - Hide/disable action controls lacking permission; render only menu items the user is permitted
    - _Requirements: 3.1.1, 3.1.2_

  - [x] 11.3 Implement `<ProtectedRoute>` route guard with access-denied view
    - Block direct navigation to unauthorized routes and show access-denied indication
    - _Requirements: 3.1.4_

  - [x] 11.4 Write property test for navigation/action exposure
    - **Property 12: Navigation and action controls expose only permitted items**
    - **Validates: Requirements 3.1.1, 3.1.2**

  - [x] 11.5 Write unit tests for permission bootstrap and route guard
    - Permission fetch after login, route guard block, refresh-without-logout
    - _Requirements: 3.1.3, 3.1.4, 3.1.5_

- [x] 12. Checkpoint — P0 complete
  - Ensure all tests pass, ask the user if questions arise.

### Phase P1 — Automation and Multi-Store Breadth

- [x] 13. Implement the scheduling engine
  - [x] 13.1 Implement `CronEvaluator` for recurrence validation and next-run computation
    - Validate well-formedness; compute next run strictly after the base time
    - _Requirements: 4.1.4, 4.1.2, 4.1.5_

  - [x] 13.2 Write property test for recurrence validation and next-run
    - **Property 13: Recurrence validation and next-run computation are well-defined**
    - **Validates: Requirements 4.1.2, 4.1.4, 4.1.5**

  - [x] 13.3 Implement `ScheduleEngine` polling `sync_schedules` with `@EnableScheduling`
    - `tick` runs due+enabled schedules, updates `last_run_at`/`next_run_at`; skip disabled; record failures and still compute next run
    - Guard double execution with a DB/Redis lock; dispatch to `SyncJobRunner`
    - _Requirements: 4.1.1, 4.1.2, 4.1.3, 4.1.5_

  - [x] 13.4 Implement schedule management and manual trigger endpoints
    - `upsertSchedule` (validate cron), `setEnabled` (persist), `triggerNow` (run immediately without altering recurrence); view enabled state, last/next run
    - _Requirements: 4.1.4, 4.2.1, 4.2.2, 4.2.3_

  - [x] 13.5 Write property test for enable round-trip and manual trigger
    - **Property 14: Schedule enable-state round-trips and manual triggers preserve recurrence**
    - **Validates: Requirements 4.2.2, 4.2.3**

  - [x] 13.6 Write unit tests for due/disabled dispatch and schedule view
    - Controlled-clock dispatch of due vs disabled schedules; schedule view projection
    - _Requirements: 4.1.1, 4.1.3, 4.2.1_

- [x] 14. Implement cross-store aggregation
  - [x] 14.1 Implement `AggregationService.aggregate(user, dateRange)`
    - Aggregate dashboard/profit/order metrics over accessible stores only; return totals and per-store breakdown
    - Convert via `CurrencyService` where a rate exists; emit per-currency subtotals with `unconverted` flag when missing
    - _Requirements: 5.1.1, 5.1.2, 5.1.3, 5.1.4, 5.1.5_

  - [x] 14.2 Write property test for all-stores aggregation
    - **Property 15: All-stores aggregation equals the sum over accessible stores only**
    - **Validates: Requirements 5.1.1, 5.1.2, 5.1.3, 5.1.4**

- [x] 15. Implement store switcher, grouping, and default store
  - [x] 15.1 Add store grouping and default-store schema
    - Create Flyway migration `V4__store_grouping_default.sql` adding `stores.store_group` and `users.default_store_id`
    - _Requirements: 5.2.3, 5.2.4_

  - [x] 15.2 Implement frontend store switcher with search, grouping, and default selection
    - Show only accessible stores; filter by name on search; organize by group; select default store on next login
    - _Requirements: 5.2.1, 5.2.2, 5.2.3, 5.2.4_

  - [x] 15.3 Write property test for store name search
    - **Property 16: Store search returns exactly the name-matching stores**
    - **Validates: Requirements 5.2.2**

  - [x] 15.4 Write property test for store grouping partition
    - **Property 17: Store grouping is a complete, correct partition**
    - **Validates: Requirements 5.2.4**

  - [x] 15.5 Write unit test for default-store selection
    - _Requirements: 5.2.3_

- [x] 16. Implement single-credential store discovery
  - [x] 16.1 Implement `StoreDiscoveryService.discover(connectionId, user)`
    - Call `connector.discoverStores`; create-or-reuse internal stores keyed on marketplace identifier; link to originating seller account
    - _Requirements: 6.1.1, 6.1.2, 6.1.3, 6.1.4_

  - [x] 16.2 Write property test for discovery idempotency
    - **Property 18: Store discovery is idempotent and links the marketplace identifier**
    - **Validates: Requirements 6.1.2, 6.1.3, 6.1.4**

- [x] 17. Checkpoint — P1 complete
  - Ensure all tests pass, ask the user if questions arise.

### Phase P2 — Amazon, Finance, Alerts, Account Security

- [x] 18. Implement Amazon SP-API and Ads sync
  - [x] 18.1 Add seller-account schema and Amazon connectors
    - Create Flyway migration `V5__amazon_sync.sql` adding `platform_connections.seller_account_id`
    - Implement Amazon SP-API/Ads connectors: `pullOrders`, `pullInventory`, `pullAdReports`, request signing, ad-report metric mapping to internal advertising entities
    - On expired/invalid token, record reason and mark connection requires-reauth
    - _Requirements: 8.1.1, 8.1.2, 8.1.3, 8.1.5_

  - [x] 18.2 Wire Amazon connectors into `SyncJobRunner` with idempotent upsert
    - Reuse the P0 runner; apply idempotent upsert keyed on Amazon external id
    - _Requirements: 8.1.4_

  - [x] 18.3 Write integration tests for Amazon pulls, signing, and token-expiry path
    - Pull + signed request against mock server; expired-token failure path
    - _Requirements: 8.1.1, 8.1.2, 8.1.5_

- [x] 19. Implement currency conversion
  - [x] 19.1 Add `exchange_rates` schema and `CurrencyService`
    - Create Flyway migration `V6__exchange_rates.sql` adding `exchange_rates` table
    - `convert(amount, from, to, date)` returns `ConvertedAmount` with rate effective on the date; retain original/converted/rate/effective-date; flag unconverted when no rate; obtain rate from configured source
    - _Requirements: 9.2.1, 9.2.2, 9.2.4, 9.2.5, 9.2.6_

  - [x] 19.2 Write property test for currency conversion provenance
    - **Property 20: Currency conversion uses the date-effective rate and retains full provenance**
    - **Validates: Requirements 9.2.1, 9.2.2, 9.2.4, 9.2.6**

  - [x] 19.3 Write integration/unit tests for exchange-rate source and VAT inclusion
    - Exchange-rate source wiring smoke test; VAT component inclusion in calculations
    - _Requirements: 9.2.5, 9.2.3_

- [x] 20. Implement settlement reconciliation and fee aggregation
  - [x] 20.1 Add reconciliation schema and `ReconciliationService`
    - Create Flyway migration `V7__settlement_reconciliation.sql` adding settlement currency/converted/rate/reconciliation columns
    - Aggregate referral/fulfillment/advertising fees; compare reported vs expected; flag discrepancy beyond tolerance; include aggregated fees in profit
    - _Requirements: 9.1.1, 9.1.2, 9.1.3, 9.1.4_

  - [x] 20.2 Write property test for fee aggregation and discrepancy flagging
    - **Property 19: Fee aggregation, discrepancy flagging, and profit inclusion are exact**
    - **Validates: Requirements 9.1.1, 9.1.2, 9.1.3, 9.1.4**

- [x] 21. Implement the unified alert center
  - [x] 21.1 Add alerts schema and `AlertEngine`
    - Create Flyway migration `V8__alerts.sql` adding the `alerts` table with open-alert dedup key
    - Generate stockout/ACoS/BuyBox-loss/negative-review alerts; dedup to a single open alert per (store, type, subject); resolve when condition clears; display to store-permitted users; Feishu push recording failures without losing the alert
    - _Requirements: 10.1.1, 10.1.2, 10.1.3, 10.1.4, 10.1.5, 10.1.6, 10.1.7, 10.1.8, 10.1.9_

  - [x] 21.2 Write property test for alert dedup and resolution
    - **Property 21: Alert generation is deduplicated and resolves when the condition clears**
    - **Validates: Requirements 10.1.1, 10.1.2, 10.1.7, 10.1.8**

  - [x] 21.3 Write integration/unit tests for Feishu push and BuyBox/negative-review creation
    - Feishu push success/failure; BuyBox-loss and negative-review alert creation
    - _Requirements: 10.1.6, 10.1.9, 10.1.3, 10.1.4_

- [x] 22. Implement account security
  - [x] 22.1 Add account-security schema and `LoginSecurityService` + `PasswordPolicyService`
    - Create Flyway migration `V9__account_security.sql` adding `users.failed_login_count`, `locked_until`, `twofa_enabled`, `twofa_secret`
    - Per-account failed-attempt counter and lockout; reject locked logins with reason; reset counter on success; log every attempt outcome; password strength validation
    - _Requirements: 11.1.1, 11.1.2, 11.1.3, 11.1.4, 11.1.5, 11.1.6_

  - [x] 22.2 Write property test for lockout counter
    - **Property 22: Login lockout counter is threshold-correct, resets on success, and is per-account**
    - **Validates: Requirements 11.1.1, 11.1.5, 11.1.6**

  - [x] 22.3 Write property test for password policy
    - **Property 23: Password acceptance matches the strength policy**
    - **Validates: Requirements 11.1.3**

  - [x] 22.4 Write property test for login logging
    - **Property 24: Every login attempt is logged with its outcome**
    - **Validates: Requirements 11.1.4**

  - [x] 22.5 Implement session control, 2FA, and sensitive-action confirmation
    - Logout/admin invalidation via Redis token denylist; require valid second factor when 2FA enabled; re-confirm identity for sensitive actions
    - _Requirements: 11.2.1, 11.2.2, 11.2.3, 11.2.4_

  - [x] 22.6 Write unit tests for session/2FA/sensitive-action flows
    - Logout invalidation, 2FA login, sensitive-action re-confirmation, admin session termination
    - _Requirements: 11.2.1, 11.2.2, 11.2.3, 11.2.4_

- [x] 23. Checkpoint — P2 complete
  - Ensure all tests pass, ask the user if questions arise.

### Phase P3 — Approvals and AI Write-Back

- [x] 24. Implement the approval workflow engine
  - [x] 24.1 Add approval schema and `@RequiresApproval` + `ApprovalAspect`
    - Create Flyway migration `V10__approval_workflow.sql` adding `approval_requests` and `approval_decisions`
    - Intercept governed actions: when enabled policy threshold is met, persist an `approval_request` and short-circuit into pending; execute immediately otherwise
    - _Requirements: 12.1.1_

  - [x] 24.2 Implement `ApprovalService` routing, sequencing, and completion
    - Route to policy approvers; require each level in order; execute action exactly once when all levels approve; cancel and record on rejection; prohibit self-approval; expire and cancel unapproved requests past expiration
    - _Requirements: 12.1.2, 12.1.3, 12.1.4, 12.1.5, 12.1.6, 12.1.7_

  - [x] 24.3 Write property test for approval gating and ordering
    - **Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition**
    - **Validates: Requirements 12.1.1, 12.1.3, 12.1.4, 12.1.5, 12.1.6**

  - [x] 24.4 Write unit tests for approval routing and expiration
    - Approval request routing; expiration sweep cancels the action
    - _Requirements: 12.1.2, 12.1.7_

- [x] 25. Implement AI write-back to live platforms
  - [x] 25.1 Implement `WriteBackService.apply(recommendationId)`
    - Submit change through the store's connector; record change, submitting user, and platform response in audit trail
    - On platform rejection, record reason and leave the internal record unchanged
    - Require approval before submission where governed by an enabled policy
    - _Requirements: 13.1.1, 13.1.2, 13.1.3, 13.1.4_

  - [x] 25.2 Write property test for approval-gated write-back
    - **Property 26: Approval-governed write-back and automation submit only after approval**
    - **Validates: Requirements 13.1.4, 13.2.6**

  - [x] 25.3 Write property test for platform-rejection invariance
    - **Property 27: Platform rejection leaves internal state unchanged and records the reason**
    - **Validates: Requirements 13.1.3, 13.2.7**

  - [x] 25.4 Write property test for write-back auditing
    - **Property 28: Applied and automated changes are fully audited**
    - **Validates: Requirements 13.1.2, 13.2.4**

- [x] 26. Implement automated bid and negative-keyword execution
  - [x] 26.1 Add automation-rules schema and `AutomationRunner`
    - Create Flyway migration `V11__automation_rules.sql` adding the `automation_rules` table with bid bounds
    - Evaluate enabled rules on each scheduled run; compute adjusted bid within min/max; add qualifying negative keywords; submit to live platform; record automated changes in audit; clamp out-of-bounds values; require approval where threshold met; on rejection record reason and leave internal record unchanged
    - _Requirements: 13.2.1, 13.2.2, 13.2.3, 13.2.4, 13.2.5, 13.2.6, 13.2.7_

  - [x] 26.2 Wire `AutomationRunner` into the scheduling engine
    - Dispatch automation evaluation from the `ScheduleEngine` poller
    - _Requirements: 13.2.1_

  - [x] 26.3 Write property test for bid clamping
    - **Property 29: Adjusted bids are always clamped within the rule's bounds**
    - **Validates: Requirements 13.2.2, 13.2.5**

- [x] 27. Final checkpoint — full platform
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each property test implements exactly one of the 29 design properties (100+ iterations, jqwik for Java / fast-check for TypeScript) and is tagged `Feature: core-platform-completion, Property {n}`.
- Each task references the specific requirement clauses it satisfies for traceability.
- Checkpoints close each phase (P0 → P3) for incremental validation.
- Flyway migrations are split per phase (`V3`–`V11`) so concurrent waves never edit the same migration file.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2.1", "7.1", "7.2"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.1", "8.1"] },
    { "id": 3, "tasks": ["2.4", "3.2", "3.3", "4.1", "4.4", "8.2", "8.3", "8.4", "9.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.5", "5.1", "9.2", "9.3", "9.4", "11.1"] },
    { "id": 5, "tasks": ["5.2", "5.3", "5.4", "5.5", "5.6", "11.2", "11.3"] },
    { "id": 6, "tasks": ["11.4", "11.5", "13.1", "15.1", "19.1"] },
    { "id": 7, "tasks": ["13.2", "13.3", "14.1", "15.2", "16.1", "19.2", "19.3"] },
    { "id": 8, "tasks": ["13.4", "14.2", "15.3", "15.4", "15.5", "16.2", "18.1", "20.1"] },
    { "id": 9, "tasks": ["13.5", "13.6", "18.2", "18.3", "20.2", "21.1"] },
    { "id": 10, "tasks": ["21.2", "21.3", "22.1"] },
    { "id": 11, "tasks": ["22.2", "22.3", "22.4", "22.5"] },
    { "id": 12, "tasks": ["22.6", "24.1"] },
    { "id": 13, "tasks": ["24.2", "25.1"] },
    { "id": 14, "tasks": ["24.3", "24.4", "25.2", "25.3", "25.4", "26.1"] },
    { "id": 15, "tasks": ["26.2", "26.3"] }
  ]
}
```
