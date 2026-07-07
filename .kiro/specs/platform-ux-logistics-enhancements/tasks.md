# Implementation Plan: Platform UX & Logistics Enhancements

## Overview

This plan implements six coordinated enhancements across the React/TypeScript frontend (`frontend/`) and the Java 17 / Spring Boot 3.2.5 backend (`backend-java/`): advertising-page decomposition, logistics depth, reusable table capabilities, react-query data-fetching, an audit navigation fix, and backend stub cleanup.

The work builds bottom-up: schema first, then backend logistics domain services, then table/filter/export endpoints, then the frontend data-fetching layer and reusable table components, then page decomposition and the shipment detail UI, and finally the navigation fix and backend stub cleanup. Each correctness property from the design (1–32) is implemented as a single property-based test, placed close to the code it validates.

Property tests use **jqwik** on the backend and **fast-check** with **Vitest** on the frontend, run a minimum of **100 iterations**, and are tagged with a comment in the form `Feature: platform-ux-logistics-enhancements, Property {N}: {property_text}`. All new tables are added to `backend-java/db/schema.sql` (no Flyway, `ddl-auto: none`).

## Tasks

- [x] 1. Add new logistics and table-view tables to the schema source of truth
  - [x] 1.1 Add shipment-child and carrier tables to `db/schema.sql`
    - Add `CREATE TABLE IF NOT EXISTS` for `carriers` (with `org_id` FK), `shipment_legs`, `carton_specs`, `customs_clearance` (unique `shipment_id`), `tracking_events` (nullable `leg_id`), `shipment_exceptions`, `handling_costs`, `shipment_line_items`
    - Use `CHAR(36)` PKs with `DEFAULT (UUID())`, `DATETIME(3)` timestamps, `DECIMAL` money columns, and `REFERENCES shipments(id) ON DELETE CASCADE` for shipment children; child tables inherit store scope via `shipment_id → shipments.store_id`
    - _Requirements: 19.1, 19.2, 19.3, 17.1, 17.2_

  - [x] 1.2 Add FBA columns to `shipments` and add saved-view tables to `db/schema.sql`
    - Add `fba_shipment_id`, `amazon_shipment_status`, `destination_fc_code`, `reporting_currency` columns to the `shipments` table definition
    - Add `saved_views` (unique `(user_id, table_key, name)`) and `column_configs` (unique `(user_id, table_key)`) tables keyed by user and store-independent
    - _Requirements: 16.1, 19.1, 19.2, 19.3, 17.5_

  - [x] 1.3 Write integration test loading `schema.sql` into a test MySQL
    - Verify all new tables exist with their tenant/store scoping columns and that no runtime migration is relied upon
    - _Requirements: 19.1, 19.2, 19.3_

- [x] 2. Create logistics entities, mappers, DTOs, and VOs
  - [x] 2.1 Create MyBatis-Plus/JPA entities mirroring `ShipmentEntity` conventions
    - Create `CarrierEntity`, `ShipmentLegEntity`, `CartonSpecEntity`, `CustomsClearanceEntity`, `TrackingEventEntity`, `ShipmentExceptionEntity`, `HandlingCostEntity`, `ShipmentLineItemEntity`; add FBA fields to `ShipmentEntity`
    - Use `CHAR(36)` UUID PKs via the existing UUID interceptor, `DATETIME(3)` timestamps, `BigDecimal` money
    - _Requirements: 4.1, 5.1, 6.1, 7.1, 8.1, 9.1, 16.1, 18.1_

  - [x] 2.2 Create mappers for each new entity
    - Add MyBatis-Plus mapper interfaces for carriers, legs, carton specs, customs clearance, tracking events, exceptions, handling costs, and line items
    - _Requirements: 4.3, 5.2, 6.3, 7.2, 8.1, 9.2, 16.3, 18.2_

  - [x] 2.3 Create DTOs and VOs with Jakarta Bean Validation annotations
    - Define request DTOs (`ShipmentLegDto`, `CartonSpecDto`, `CarrierDto`, `CustomsClearanceDto`, `TrackingEventDto`, `ShipmentExceptionDto`, `HandlingCostDto`, `FbaFieldsDto`) and response VOs, with `@Size`/`@DecimalMin`/`@DecimalMax`/`@Min`/`@Max` constraints matching the documented bounds
    - _Requirements: 4.1, 5.1, 6.1, 7.1, 8.1, 9.1, 16.1, 16.2, 18.1_

- [x] 3. Implement Carrier management service (org-scoped)
  - [x] 3.1 Implement `CarrierService` (create, update, list)
    - Enforce name (1–200) and service type (1–100) validation; throw `BusinessException` identifying the invalid field on failure with no persisted change; return all carriers as a list, empty list when none exist; scope each carrier to the requester's organization
    - _Requirements: 6.1, 6.2, 6.4, 6.5, 17.1_

- [x] 4. Implement multi-leg shipment transport path service
  - [x] 4.1 Implement `ShipmentLegService` upsert and ordered list
    - Persist a leg with leg_type, sequence number, carrier, departure/arrival dates, and leg cost (0.00–999,999,999.99); validate arrival ≥ departure, required fields, leg-count ≤ 20, and carrier existence; list legs ordered by sequence number; transactional rollback leaves prior data unchanged on any failure
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6, 4.7, 6.6_

  - [x] 4.2 Write property test for shipment-leg ordering
    - **Property 8: Shipment legs are ordered by sequence**
    - **Validates: Requirements 4.4**

- [x] 5. Implement carton specifications service
  - [x] 5.1 Implement `CartonSpecService` add and totals
    - Persist carton specs (1–100 per shipment) with bounded dimensions/weight/units/box-count; compute total box count and total unit quantity (sum of units-per-box × box-count); reject out-of-bounds values with field-level errors and no persisted change
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 5.2 Write property test for carton totals
    - **Property 3: Carton totals equal the per-spec sums**
    - **Validates: Requirements 5.3, 5.4**

- [x] 6. Implement customs clearance service
  - [x] 6.1 Implement `CustomsClearanceService` update and get
    - Persist clearance status (one of not-started, declared, in-review, cleared, held), declaration reference (1–100), and duties/taxes (0.00–999,999,999.99); reject invalid status leaving prior record unchanged; return a not-started state when no record exists
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.6_

  - [x] 6.2 Write property test for customs status round-trip
    - **Property 6: Customs status update round-trips for permitted values**
    - **Validates: Requirements 7.2, 7.6**

- [x] 7. Implement shipment tracking trajectory service
  - [x] 7.1 Implement `TrackingEventService` add and ordered list
    - Persist up to 1,000 events with timestamp, description (1–500), and optional leg reference; validate timestamp/description; list events newest-first with a most-recently-recorded tiebreaker for equal timestamps; allow unset leg reference
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

  - [x] 7.2 Write property test for tracking-event ordering
    - **Property 9: Tracking events are ordered most-recent first with a stable tiebreaker**
    - **Validates: Requirements 8.4, 8.5**

- [x] 8. Implement shipment exceptions service
  - [x] 8.1 Implement `ShipmentExceptionService` raise, resolve, and list
    - Persist exceptions (type one of delay/damage/customs-hold, description 1–1000) in the open state; resolve transitions open→resolved recording the resolving `Audit_Context`; reject re-resolving an already-resolved exception unchanged; list only exceptions of the Active_Store
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.7_

  - [x] 8.2 Write property test for exception resolution transition
    - **Property 7: Resolving an exception is a one-way transition**
    - **Validates: Requirements 9.4, 9.5**

- [x] 9. Implement handling cost service
  - [x] 9.1 Implement `HandlingCostService` upsert, delete, and list
    - Persist handling-cost lines with amount (0.00–999,999,999.99), 3-char currency code, and description/category (1–200); reject invalid values leaving prior lines unchanged; return each line with amount, currency, and description
    - _Requirements: 18.1, 18.2, 18.3, 18.4_

- [x] 10. Implement FBA core fields and line items service
  - [x] 10.1 Implement `FbaShipmentService` save and read of FBA fields plus line items
    - Persist FBA shipment id (1–100), Amazon status, destination FC code (1–50), and 0+ line items (SKU/MSKU 1–100, ASIN 1–20, quantity 1–1,000,000); reject invalid/over-length fields leaving prior values unchanged; return only fields the requester is authorized to read for the Active_Store
    - _Requirements: 16.1, 16.2, 16.3, 16.5, 16.6_

- [x] 11. Validate logistics persistence and rejection behavior
  - [x] 11.1 Write property test for valid logistics round-trip
    - **Property 4: Valid logistics records round-trip through persistence**
    - **Validates: Requirements 4.3, 5.2, 6.3, 9.2, 16.3, 18.2, 18.3**

  - [x] 11.2 Write property test for invalid logistics input rejection
    - **Property 5: Invalid logistics input is rejected without side effects**
    - **Validates: Requirements 4.1, 4.5, 4.6, 4.7, 5.1, 5.5, 6.1, 6.2, 6.6, 7.1, 7.3, 8.1, 8.2, 9.1, 9.3, 16.1, 16.2, 16.5, 18.1, 18.4**

- [x] 12. Implement shipment cost chain computation
  - [x] 12.1 Implement `CostChainService.compute`
    - Sum leg costs + customs duties/taxes + handling-cost lines into a total in the shipment's reporting currency; treat missing amounts as zero; convert non-reporting-currency components using the per-component rate (else the documented store rate at the cost date), retaining original amount/currency; round converted amounts to 2 dp half-up; shipment-level only (no SKU allocation); not-found/denied for unknown or out-of-scope shipments
    - _Requirements: 10.1, 10.2, 10.4, 10.5, 10.6, 10.7, 18.5_

  - [x] 12.2 Write property test for cost-chain total
    - **Property 1: Cost chain total equals the sum of its components**
    - **Validates: Requirements 10.1, 10.2, 10.6, 18.5**

  - [x] 12.3 Write property test for currency conversion
    - **Property 2: Currency conversion preserves the original and rounds half-up**
    - **Validates: Requirements 10.4, 10.5**

- [x] 13. Wire the LogisticsController and enforce tenant/store isolation
  - [x] 13.1 Create `LogisticsController` endpoints with `DataScopeService` enforcement
    - Expose carrier, leg, carton, customs, tracking, exception, handling-cost, FBA, and cost-chain endpoints under `/api`; resolve and enforce scope via `DataScopeService.applyScope`/`assertCanRead`/`assertCanWrite` on every read/write; return not-found/denied with no record data for out-of-scope requests
    - _Requirements: 4.3, 5.2, 6.3, 6.4, 7.2, 8.4, 9.7, 10.2, 10.8, 16.6, 17.1, 17.2, 17.3, 17.4_

  - [x] 13.2 Write property test for scoped record visibility
    - **Property 20: Records are visible only within the requester's scope**
    - **Validates: Requirements 8.6, 9.7, 10.8, 16.6, 17.1, 17.2, 17.3, 17.4**

- [x] 14. Checkpoint - Ensure all logistics tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 15. Implement saved views and column configuration (backend)
  - [x] 15.1 Implement `SavedView`/`ColumnConfig` entities, mappers, `TableViewService`, and `TableViewController`
    - Persist saved views (name 1–100, unique per user+table key) and column configs keyed by `(user_id, table_key)`, store-independent; reject empty/over-length/duplicate names without altering existing views; never return another user's records
    - _Requirements: 2.11, 2.12, 2.13, 2.14, 17.5_

  - [x] 15.2 Write property test for saved-view round-trip and name validation
    - **Property 17: Saved views round-trip and reject invalid or duplicate names**
    - **Validates: Requirements 2.11, 2.13, 2.14**

  - [x] 15.3 Write property test for per-user isolation of views and column configs
    - **Property 21: Saved views and column configs are isolated per user**
    - **Validates: Requirements 2.12, 17.5**

- [x] 16. Implement server-side advanced filtering, export, and select-all-matching
  - [x] 16.1 Implement filter-condition validation and scoped query translation
    - Validate each `FilterCondition` (field exists, operator valid for field type, value type matches), reject invalid conditions identifying the invalid part; translate valid conditions into a scoped `LambdaQueryWrapper` combined with AND for the `POST /api/{resource}/query` endpoint
    - _Requirements: 2.9, 2.10_

  - [x] 16.2 Implement server-side CSV export over the full filtered result set
    - Produce a `text/csv` response over all pages of the filtered, sorted result, restricted to the supplied visible columns in their configured order, using paged/streaming reads
    - _Requirements: 2.8_

  - [x] 16.3 Implement select-all-matching resolution from the filter descriptor
    - Resolve "select all matching the current filter" to every record satisfying the active filter across all pages so bulk operations target the full matching set rather than the rendered page
    - _Requirements: 2.7_

  - [x] 16.4 Write property test for advanced filtering results
    - **Property 10: Advanced filtering returns exactly the matching rows**
    - **Validates: Requirements 2.9**

  - [x] 16.5 Write property test for invalid filter rejection
    - **Property 11: Invalid filter conditions are rejected without changing displayed rows**
    - **Validates: Requirements 2.10**

  - [x] 16.6 Write property test for export fidelity
    - **Property 12: Export reflects the full filtered, sorted result over visible columns**
    - **Validates: Requirements 2.8**

  - [x] 16.7 Write property test for select-all-matching resolution
    - **Property 13: Select-all-matching resolves to the full filtered set, not the current page**
    - **Validates: Requirements 2.7**

- [x] 17. Checkpoint - Ensure all table-capability tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Implement the react-query data-fetching layer
  - [x] 18.1 Create `queryClient.ts` and mount `QueryClientProvider` at the app root
    - Configure default staleTime 30s, gcTime 300s, query retry 3, mutation retry 0, `refetchOnWindowFocus: false`; wrap `RouterProvider` in `App.tsx` with a single `QueryClient`
    - _Requirements: 3.1, 3.5, 3.6, 3.9_

  - [x] 18.2 Create `queryKeys.ts` convention and `useApiQuery`/`useApiMutation` hooks
    - Define `[resource, storeId, ...params]` query keys; expose loading/error/unwrapped-data from `useApiQuery`; provide cache invalidation on write success and a manual mutation retry via `mutate` re-invocation; coexist with hand-rolled pages
    - _Requirements: 3.2, 3.3, 3.4, 3.7, 3.8, 3.10_

  - [x] 18.3 Write property test for query-key uniqueness
    - **Property 31: Query keys are unique per resource and store scope**
    - **Validates: Requirements 3.8**

  - [x] 18.4 Write property test for read-failure cache retention
    - **Property 30: Read failures preserve the last cached data and surface a message**
    - **Validates: Requirements 3.4**

  - [x] 18.5 Write unit tests for data-fetching behaviors
    - Cover loading state, write invalidation/refresh, read auto-retry, mutation manual-retry, and migrated-page behavior equivalence
    - _Requirements: 3.2, 3.3, 3.5, 3.6, 3.7_

- [x] 19. Implement SharedDataTable and supporting table hooks
  - [x] 19.1 Implement `useColumnConfig` hook
    - Hide/show/reorder columns while enforcing the at-least-one-visible invariant; reject hiding the last visible column with an indication and no state change
    - _Requirements: 2.2, 2.3_

  - [x] 19.2 Implement `SharedDataTable` component
    - Render supplied rows and column definitions; support 1–5 pinned columns held fixed at the table start with the rest scrolling; accumulate selected row ids and pass them to bulk operations; render skeleton (loading), empty-state (zero rows), and error-with-retry states; standalone importable module under `app/components/table/`
    - _Requirements: 1.5, 1.6, 1.7, 1.9, 2.1, 2.4, 2.5, 2.6, 2.15_

  - [x] 19.3 Implement `useSavedViews` hook wired to the table-view endpoints
    - List, save, and apply a saved view's column configuration, filters, and sort order; surface naming-conflict/limit indications from the backend
    - _Requirements: 2.11, 2.13, 2.14_

  - [x] 19.4 Write property test for the visible-column invariant
    - **Property 15: At least one column always remains visible**
    - **Validates: Requirements 2.2, 2.3**

  - [x] 19.5 Write property test for pinned columns
    - **Property 16: Pinned columns are held at the start of the table**
    - **Validates: Requirements 2.4**

  - [x] 19.6 Write property test for bulk-operation selection
    - **Property 14: A bulk operation receives exactly the selected row identifiers**
    - **Validates: Requirements 2.5**

  - [x] 19.7 Write property test for empty-state rendering
    - **Property 18: Empty result sets render an empty state, never an error**
    - **Validates: Requirements 1.6, 2.15**

- [x] 20. Implement FilterToolbar component
  - [x] 20.1 Implement `FilterToolbar` as a standalone module
    - Present and manage search, type selectors, and advanced-filter controls; emit the resulting filter state; surface invalid advanced-filter conditions inline without changing displayed rows
    - _Requirements: 1.3, 1.9, 2.9, 2.10_

- [x] 21. Implement BulkActionBar component
  - [x] 21.1 Implement `BulkActionBar` as a standalone module
    - Display the bar with applicable bulk operations only when one or more rows are selected; reject activating a bulk operation with no selection, indicating at least one row must be selected
    - _Requirements: 1.4, 1.9, 2.5, 2.6_

  - [x] 21.2 Write property test for bulk-action-bar visibility
    - **Property 19: The bulk-action bar is visible exactly when rows are selected**
    - **Validates: Requirements 1.4**

- [x] 22. Decompose the Campaigns Workspace
  - [x] 22.1 Create the `CampaignsWorkspace` tab container
    - Render exactly the eleven tabs (广告活动, AI托管, 广告组, 推广商品, 投放, 否定投放, 搜索词, 购买的其他商品, 竞价调整, SP预算上限, 操作日志) in order; display only the selected tab's content
    - _Requirements: 1.1, 1.2_

  - [x] 22.2 Extract per-tab components using the reusable building blocks
    - Move each tab's table, filters, and bulk actions into standalone tab modules composing `SharedDataTable`, `FilterToolbar`, and `BulkActionBar`, preserving existing per-tab behavior
    - _Requirements: 1.3, 1.4, 1.9_

  - [x] 22.3 Wire tabs to the data-fetching layer
    - Use `useApiQuery`/`useApiMutation` so each tab shows a skeleton with refresh disabled while loading, an empty-state for zero records, an error indicator with retry that leaves prior records unchanged, and a working refresh control
    - _Requirements: 1.5, 1.6, 1.7, 1.8_

  - [x] 22.4 Write unit/example tests for the workspace
    - Cover tab taxonomy/order, tab isolation, skeleton/refresh-disable, and error-retry/refresh interactions
    - _Requirements: 1.1, 1.2, 1.5, 1.7, 1.8_

- [x] 23. Build the shipment detail UI and migrate the FBA shipments page
  - [x] 23.1 Create `ShipmentDetailPage` with all logistics sections
    - Render legs (ordered by sequence, empty-leg state), carton specs with computed totals, customs clearance (not-started when absent), tracking trajectory (most-recent-first, empty-trajectory state), exceptions, cost chain (itemized + total), and FBA fields with line items, using the data-fetching layer
    - _Requirements: 4.4, 4.8, 5.3, 5.4, 7.5, 7.6, 8.7, 8.8, 10.3, 16.4_

  - [x] 23.2 Migrate `FbaShipmentsPage` to the data-fetching layer and add the exception indicator
    - Preserve existing store-scoping/loading/empty/error behavior; display an exception indicator for shipments with one or more open exceptions
    - _Requirements: 3.7, 9.6_

  - [x] 23.3 Write unit/example tests for detail rendering and empty states
    - Cover rendering and empty/not-started states for legs, cartons, customs, tracking, exceptions, cost chain, and FBA fields
    - _Requirements: 4.8, 5.4, 7.6, 8.8, 9.6, 10.3, 16.4_

- [x] 24. Fix Command Center audit navigation
  - [x] 24.1 Repoint Command Center `/audit` links to `/audit-rollback`
    - Change the `审计日志` quick-action target and the "view all recent operations" link in `CommandCenterPage.tsx` to the registered `/audit-rollback` route; ensure click and keyboard (Enter/Space) activation navigate there
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

  - [x] 24.2 Write property test for audit-link route resolution
    - **Property 32: Command Center audit links resolve to a registered route**
    - **Validates: Requirements 11.1, 11.2, 11.3, 11.4**

- [x] 25. Replace the advertising "system" stub with the real audit context
  - [x] 25.1 Resolve the acting user from `SecurityUtils` in the four controllers
    - In `GoalController`, `KeywordController`, `RecommendationController`, `SearchTermController`, replace `userId = "system"` with `SecurityUtils.getCurrentUserId()` resolved before the service call; store it as acting-user attribution on the audit record; reject unresolved-user permission-gated requests with an authorization error and no data change; use a reserved `SYSTEM_ACTOR` identifier for genuine non-interactive triggers
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [x] 25.2 Write property test for acting-user attribution
    - **Property 22: Authenticated advertising operations are attributed to the acting user**
    - **Validates: Requirements 12.1, 12.2, 12.4**

  - [x] 25.3 Write property test for unresolvable-user rejection
    - **Property 23: Operations with an unresolvable user are rejected without side effects**
    - **Validates: Requirements 12.3**

  - [x] 25.4 Write example test for the non-interactive actor path
    - Verify a scheduled/background trigger records the reserved actor distinct from any authenticated user id
    - _Requirements: 12.5_

- [x] 26. Implement the real platform connection test
  - [x] 26.1 Rewrite `ApiSyncServiceImpl.testPlatformConnection(String id)` to use the connector
    - Read the connection, decrypt its config, and call `platformConnector.test(platform, config)` so both entry points (by key, by id) converge on the connector; return a structured result (ok + message) within 30s; report success only on a connector success; return failure for rejected/incomplete credentials or unreachable/timeout leaving stored credentials unchanged; return not-found for unknown key/id; change the `/test` endpoint to return the result object
    - _Requirements: 13.1, 13.2, 13.3, 13.5, 13.6_

  - [x] 26.2 Write property test for connector-result fidelity
    - **Property 24: A connection test faithfully reflects the platform connector result**
    - **Validates: Requirements 13.1, 13.3, 13.6**

  - [x] 26.3 Write property test for unknown-target not-found
    - **Property 25: A connection test for an unknown target returns not-found**
    - **Validates: Requirements 13.4**

  - [x] 26.4 Write example tests for success and timeout messages
    - Cover the human-readable success message and the unreachable/timeout failure message
    - _Requirements: 13.2, 13.5_

- [x] 27. Implement the real Insight Agent contract
  - [x] 27.1 Harden `InsightAgentServiceImpl.query`
    - When AI is enabled, return AI-generated insights with at most 10 recommended actions within 30s marked source `ai`; validate AI output against `InsightResultVo` and treat non-conforming output, failure, timeout, or AI-disabled as a `degraded` result recording the reason and retaining the query; return zero actions plus an explanation when no action is appropriate; exclude secrets from prompts and results; persist every result to saved-insights history, still returning the result and recording the reason on persistence failure
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10_

  - [x] 27.2 Write property test for output structure and source marker
    - **Property 26: Insight Agent results conform to the output structure and source marker**
    - **Validates: Requirements 14.3, 14.4, 14.5, 14.7**

  - [x] 27.3 Write property test for the recommended-action cap
    - **Property 27: AI-generated insights return at most ten recommended actions**
    - **Validates: Requirements 14.1**

  - [x] 27.4 Write property test for secret hygiene
    - **Property 28: Insight Agent never leaks secrets**
    - **Validates: Requirements 14.8**

  - [x] 27.5 Write property test for history persistence
    - **Property 29: Every Insight Agent result is persisted to history**
    - **Validates: Requirements 14.9**

  - [x] 27.6 Write example tests for no-data and failure paths
    - Cover the AI no-data explanation, the degraded-on-failure path, and the persistence-failure path
    - _Requirements: 14.2, 14.6, 14.10_

- [x] 28. Externalize and protect the Amazon Ads LWA credentials
  - [x] 28.1 Bind LWA credentials to environment variables and guard blank values
    - In `application.yml`, set `adpilot.amazon-ads.client-id` to `${ADPILOT_AMAZON_ADS_CLIENT_ID:}` and `client-secret` to `${ADPILOT_AMAZON_ADS_CLIENT_SECRET:}` with no literal secret; never log or return the secret; reject operations requiring LWA credentials while blank with a 4xx "Amazon Ads credentials are not configured" (no unhandled 500)
    - Note: the previously committed secret must be rotated out-of-band so the value in source history is no longer valid (operational, verified outside code) — _Requirements: 15.5_
    - _Requirements: 15.1, 15.2, 15.3, 15.4_

  - [x] 28.3 Write smoke tests for credential externalization
    - Assert the committed config contains no literal client-id/secret and that a blank-credential operation returns a 4xx rather than a 500
    - _Requirements: 15.1, 15.4_

- [x] 29. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- Property tests run a minimum of 100 iterations and are tagged `Feature: platform-ux-logistics-enhancements, Property {N}`; backend uses jqwik, frontend uses fast-check + Vitest.
- All new tables live in `backend-java/db/schema.sql`; no Flyway or `ddl-auto` is relied upon.
- Checkpoints (tasks 14, 17, 29) ensure incremental validation at natural boundaries.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "2.2", "2.3"] },
    { "id": 3, "tasks": ["3.1", "4.1", "5.1", "6.1", "7.1", "8.1", "9.1", "10.1", "15.1", "18.1", "24.1", "25.1", "26.1", "27.1", "28.1"] },
    { "id": 4, "tasks": ["11.1", "11.2", "12.1", "13.1", "16.1", "16.2", "16.3", "18.2", "19.1", "20.1", "21.1", "4.2", "5.2", "6.2", "7.2", "8.2", "15.2", "15.3", "24.2", "25.2", "25.3", "25.4", "26.2", "26.3", "26.4", "27.2", "27.3", "27.4", "27.5", "27.6", "28.3"] },
    { "id": 5, "tasks": ["12.2", "12.3", "13.2", "16.4", "16.5", "16.6", "16.7", "18.3", "18.4", "18.5", "19.2", "19.3", "21.2"] },
    { "id": 6, "tasks": ["19.4", "19.5", "19.6", "19.7", "22.1", "23.1", "23.2"] },
    { "id": 7, "tasks": ["22.2", "23.3"] },
    { "id": 8, "tasks": ["22.3"] },
    { "id": 9, "tasks": ["22.4"] }
  ]
}
```
