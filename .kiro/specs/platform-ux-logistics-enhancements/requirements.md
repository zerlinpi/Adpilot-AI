# Requirements Document

## Introduction

This feature is a coordinated set of platform enhancements for the **AdPilot AI** cross-border e-commerce operations platform, spanning the React/TypeScript frontend (`frontend/`) and the Java 17 / Spring Boot 3.2.5 backend (`backend-java/`, MyBatis-Plus + JPA + MySQL 8.0 + Redis). The database layer is decoupled from the application: `spring.flyway.enabled`, `spring.sql.init.mode`, and `spring.jpa.hibernate.ddl-auto` are all disabled (`false` / `never` / `none`) across `application.yml`, `application-dev.yml`, and `application-prod.yml`, so the application never runs migrations or auto-creates schema at runtime. `backend-java/db/schema.sql` is the single source of truth for the schema and is imported manually. Flyway is present only as a (disabled) dependency. The work targets six areas confirmed against the live codebase:

1. **Advertising page refactor** — `CampaignsPage.tsx` is a very large, highly coupled file (multiple inline tab components, table markup, filter controls, and bulk actions in one module). It is to be decomposed into a tabbed workspace, a reusable shared table, a filter toolbar, and a bulk-action bar, with behavior preserved.
2. **Logistics / FBA shipment depth** — `FbaShipmentsPage.tsx` and its backing `ShipmentVo` are a flat shipment list (id, type, carrier, tracking number, ship date, ETA, quantity, cost). The platform lacks 领星-style (Lingxing-style) logistics depth: first-leg (头程) and last-leg (尾程) legs, box/carton specifications (箱规), carriers (承运商) as managed entities, customs clearance (清关), tracking trajectory (轨迹), exceptions (异常), and an end-to-end cost chain (费用链路).
3. **Reusable table capabilities** — Tables across the application are basic HTML tables. They lack saved views, column configuration, bulk operations, export, pinned/frozen columns, and advanced filtering. These capabilities are to be provided as reusable shared table building blocks.
4. **Data-fetching unification** — `@tanstack/react-query` (v5) is already a declared dependency, but no `QueryClientProvider` is mounted and no `useQuery`/`useMutation` is used anywhere; dozens of pages hand-roll `useEffect` + `loading` + `error` + manual reload. The data-request pattern is to be standardized on react-query.
5. **Navigation bug fix** — `CommandCenterPage.tsx` has quick-entry links to `/audit`, but the registered route (in `routes.tsx` and the sidebar in `Layout.tsx`) is `/audit-rollback`. The `/audit` links resolve to no route.
6. **Backend stub / TODO cleanup** — Confirmed stubs: advertising controllers (`GoalController`, `KeywordController`, `RecommendationController`, `SearchTermController`) hardcode `String userId = "system"` with a `// TODO: get from auth context` comment; `ApiSyncServiceImpl.testPlatformConnection(String id)` only logs with a `// TODO: integrate with actual platform API` comment; and `InsightAgentServiceImpl` returns a deterministic `generatedBy:"stub"` result. These are to be replaced with the real authenticated audit context and real implementations.

### Scope boundaries and overlap with existing specs

Three existing specs partially touch these areas, and this spec is scoped to avoid duplicating their delivered work:

- `project-fix-and-cleanup` — infrastructure, MySQL `schema.sql` consolidation, build/deploy. Schema **consolidation/build/deploy** is owned there and is **out of scope here**. However, because this spec introduces many new logistics tables (shipment legs, carton specs, carriers, customs clearance, tracking events, exceptions, handling costs, FBA fields, saved views), this spec owns its own schema-change strategy for those **new feature tables**: they are added to `db/schema.sql` here, consistent with the project's no-Flyway, `schema.sql`-as-single-source-of-truth approach (see Requirement 19).
- `core-platform-completion` — platform data sync, RBAC enforcement, scheduling, alerts, write-back. The real platform connection test relies on the `PlatformConnector.test(...)` contract delivered there; this spec consumes it, and does not redefine sync.
- `app-functionality-completion` — wiring half-built pages to existing endpoints, the SparkX-modeled advertising tab taxonomy (Requirement 19), and the Insight Agent (Requirement 24). The `CampaignsPage` tab taxonomy and Insight Agent behavior originate there.

This spec covers the **structural quality, depth, reusability, and stub-cleanup** dimensions: decomposing the advertising page into reusable components without changing its tab taxonomy, deepening the logistics data model, extracting reusable table capabilities, standardizing data fetching, fixing one navigation bug, and replacing the named backend stubs with real audit context and implementations. Where this spec changes a surface already owned by another spec, design MUST reconcile rather than duplicate.

Acceptance criteria are written in EARS format and are technology-neutral about implementation while remaining aware of the existing stack.

## Glossary

- **System**: The AdPilot AI application as a whole (frontend + backend) unless a more specific component is named.
- **Frontend**: The React + TypeScript single-page application in `frontend/` that consumes the Backend under the `/api` prefix.
- **Backend**: The Spring Boot service in `backend-java/` exposing REST endpoints under `/api`.
- **Active_Store**: The Store currently selected in the header store switcher, used to scope store-scoped page data.
- **Campaigns_Workspace**: The advertising management page currently implemented as `CampaignsPage.tsx`, comprising a set of tabs (广告活动, AI托管, 广告组, 推广商品, 投放, 否定投放, 搜索词, 购买的其他商品, 竞价调整, SP预算上限, 操作日志).
- **Shared_Data_Table**: A reusable Frontend table component (and its supporting hooks) that renders tabular records and provides the Table_Capabilities, intended to replace ad hoc per-page table markup.
- **Filter_Toolbar**: A reusable Frontend component that presents and manages a table's active filter controls (search, type selectors, advanced filters) and emits the resulting filter state.
- **Bulk_Action_Bar**: A reusable Frontend component that appears when one or more table rows are selected and exposes Bulk_Operations applicable to the selection.
- **Table_Capabilities**: The set of reusable table behaviors: Saved_View, Column_Configuration, Bulk_Operations, Data_Export, Pinned_Columns, and Advanced_Filtering.
- **Saved_View**: A named, persisted combination of a table's column configuration, filters, and sort order that a user can store and re-apply.
- **Column_Configuration**: A per-user choice of which columns are visible and their order within a Shared_Data_Table.
- **Bulk_Operations**: Actions applied to a set of selected rows in a single user gesture (for example, bulk pause, bulk status change).
- **Data_Export**: Producing a downloadable file (CSV) of a table's current rows respecting the active Column_Configuration and filters.
- **Pinned_Columns**: Columns held fixed (frozen) at the start of a Shared_Data_Table while the remaining columns scroll horizontally.
- **Advanced_Filtering**: Composing one or more field-level filter conditions (field, operator, value) that the table applies in combination.
- **Data_Fetching_Layer**: The standardized Frontend data-request mechanism built on `@tanstack/react-query`, comprising a mounted query client, query hooks for reads, and mutation hooks for writes.
- **Query_Client**: The single `@tanstack/react-query` client instance provided to the Frontend component tree.
- **Logistics_Module**: The Backend and Frontend capability for managing FBA and cross-border shipments, currently surfaced by `FbaShipmentsPage.tsx` and backed by the shipment entity/`ShipmentVo`.
- **Shipment**: A logistics consignment moving inventory toward an Amazon (FBA) or other destination, owned by a Store.
- **First_Leg**: The head-haul (头程) portion of a Shipment from origin to a transit or port handover point.
- **Last_Leg**: The final-delivery (尾程) portion of a Shipment from the destination port/hub to the receiving warehouse or fulfillment center.
- **Shipment_Leg**: One ordered segment of a Shipment's transport path, with a leg_type (for example, 头程/first leg, forwarder, ocean/air freight, customs, 尾程/last leg), a sequence number, an assigned Carrier, departure and arrival dates, and a leg cost.
- **Carton_Spec**: The box/carton specification (箱规) of a Shipment: per-box dimensions, weight, units per box, and box count.
- **Carrier**: A承运商 (transport provider) responsible for moving a Shipment_Leg, identified by name and service type.
- **Customs_Clearance**: The清关 record of a Shipment, including declaration status, clearance milestones, and duties/taxes.
- **Tracking_Event**: A timestamped trajectory (轨迹) entry recording a Shipment's location or status change.
- **Shipment_Exception**: An异常 raised against a Shipment (for example, delay, damage, customs hold, missing documents) with a type and resolution state.
- **Cost_Chain**: The end-to-end费用链路 of a Shipment: the itemized costs across its legs, customs, and handling, aggregated to a total landed logistics cost.
- **Audit_Context**: The authenticated acting user's identity resolved from the security context for audit and created-by/updated-by attribution, exposed by `SecurityUtils` (`getCurrentUserId` / `getCurrentUserIdOrNull`).
- **Audit_Trail**: The persisted record of who performed an action, surfaced through the audit log.
- **Insight_Agent**: The conversational AI analyst feature (`InsightAgentServiceImpl`, `/api/insight-agent`) that answers store-scoped data queries with insights and recommended actions.
- **AI_Provider**: The configured external AI model accessed through `AiAssistService`.
- **Platform_Connection**: A stored, encrypted credential plus configuration linking a Store to an external Platform, managed via `ApiSyncService`.
- **Platform_Connection_Test**: An operation that validates a Platform_Connection's stored credentials against the external platform and reports the outcome.
- **Organization**: The tenant that owns one or more Stores; the top-level isolation boundary. Organization-level records are shared across all Stores of the same Organization.
- **Active_Store_Scope**: The authorization scope resolved by the existing `DataScopeService`/`EffectiveScope` model that determines which Organization and Stores a requester may read or write.
- **LWA_Credentials**: The application's own Login-with-Amazon (LWA) OAuth client credentials (`adpilot.amazon-ads.client-id` and `adpilot.amazon-ads.client-secret`) used to drive the Amazon Ads OAuth flow. Distinct from per-Store Platform_Connection credentials.
- **FBA_Shipment_Fields**: The core Amazon FBA attributes recorded on a Shipment: FBA shipment identifier, Amazon shipment status, destination fulfillment-center (FC) code, and shipment line items.
- **Fulfillment_Center**: An Amazon fulfillment center (FC) identified by its FC code, used as a Shipment's destination.
- **Shipment_Line_Item**: A per-Shipment line entry recording a SKU, ASIN, and MSKU together with a quantity.
- **Handling_Cost**: A first-class, manageable cost line on a Shipment with an amount, a currency code, and a description/category, included in the Cost_Chain.
- **Reporting_Currency**: The currency in which a Shipment's Cost_Chain total is expressed.
- **Query_Key**: The cache identity assigned to a read in the Data_Fetching_Layer, expressed as a tuple/array of `[resource, storeId, ...params]` (for example, `['campaigns', storeId, filters]`).

## Requirements

### Requirement 1: Decompose the Campaigns Workspace into Reusable Components

**User Story:** As a frontend developer, I want the advertising Campaigns_Workspace decomposed into a tab container, a shared table, a filter toolbar, and a bulk-action bar, so that the page is maintainable and its building blocks are reusable.

The current `CampaignsPage.tsx` defines its tab components, table markup, filter controls, and bulk actions inline in a single large module. Design MUST inventory the existing tabs and confirm the target component boundaries, preserving the existing tab taxonomy owned by `app-functionality-completion` Requirement 19.

#### Acceptance Criteria

1. THE Campaigns_Workspace SHALL render a tab container presenting exactly the eleven tabs 广告活动, AI托管, 广告组, 推广商品, 投放, 否定投放, 搜索词, 购买的其他商品, 竞价调整, SP预算上限, and 操作日志, in that order, with each tab's records displayed in that tab's view.
2. WHEN an operator selects a tab in the Campaigns_Workspace, THE Frontend SHALL display only that tab's content, rendering tabs that present tabular records through the Shared_Data_Table.
3. THE Campaigns_Workspace SHALL present its filter controls through the Filter_Toolbar component.
4. WHILE one or more rows are selected via their row checkboxes in a Campaigns_Workspace table that supports Bulk_Operations, THE Frontend SHALL display the Bulk_Action_Bar with the Bulk_Operations applicable to the selected rows, and SHALL hide the Bulk_Action_Bar when no rows are selected.
5. WHILE a Campaigns_Workspace tab is loading its records, THE Frontend SHALL display a skeleton loading placeholder for that tab and SHALL disable that tab's refresh control until loading completes.
6. WHERE a Campaigns_Workspace tab has zero records for the active filters, THE Frontend SHALL display an empty-state view for that tab rather than an error.
7. IF loading a Campaigns_Workspace tab's records fails, THEN THE Frontend SHALL display an error indicator with a retry control for that tab, leave the previously displayed records unchanged, and re-request that tab's records when the retry control is activated.
8. WHEN an operator activates a Campaigns_Workspace tab's refresh control, THE Frontend SHALL re-request that tab's records and display the refreshed result.
9. THE Shared_Data_Table, Filter_Toolbar, and Bulk_Action_Bar components SHALL be defined as standalone modules that are importable by pages other than the Campaigns_Workspace.

### Requirement 2: Reusable Shared Table Capabilities

**User Story:** As an operator, I want tables across the platform to support saved views, column configuration, bulk operations, export, pinned columns, and advanced filtering, so that I can work with large datasets efficiently and consistently.

#### Acceptance Criteria

1. THE Shared_Data_Table SHALL render a supplied set of records as rows and a supplied set of column definitions as columns.
2. WHEN an operator changes the Column_Configuration by hiding, showing, or reordering columns, THE Shared_Data_Table SHALL render only the visible columns in the configured order, retaining at least one visible column at all times.
3. IF an operator attempts to hide the last remaining visible column, THEN THE Shared_Data_Table SHALL reject the change, keep that column visible, and present an indication that at least one column must remain visible.
4. WHEN an operator designates 1 to 5 columns as Pinned_Columns, THE Shared_Data_Table SHALL hold those columns fixed at the start of the table while the remaining columns scroll horizontally.
5. WHEN an operator selects rows and activates a Bulk_Operation exposed by the host page, THE Shared_Data_Table SHALL pass the set of selected row identifiers to that operation.
6. IF an operator activates a Bulk_Operation when no rows are selected, THEN THE Shared_Data_Table SHALL not invoke the operation and SHALL present an indication that at least one row must be selected.
7. WHEN an operator activates a "select all matching the current filter" selection under pagination, THE Shared_Data_Table SHALL define the selection by the active Advanced_Filtering conditions rather than only the rows rendered on the current page, so that the Bulk_Operation applies to every record matching the current filter across all pages.
8. WHEN an operator activates Data_Export, THE Backend SHALL produce a CSV file server-side containing the full filtered result set (not only the current page) limited to the visible columns and reflecting the active Advanced_Filtering conditions and sort order.
9. WHEN an operator composes an Advanced_Filtering condition consisting of a field, an operator, and a value, THE Backend SHALL evaluate the conditions server-side and THE Shared_Data_Table SHALL display only the rows that satisfy all active conditions in combination.
10. IF an operator submits an Advanced_Filtering condition with a missing field, missing operator, or a value whose type does not match the selected field, THEN THE Shared_Data_Table SHALL reject the condition, leave the currently displayed rows unchanged, and present an indication of which part of the condition is invalid.
11. WHEN an operator saves the current columns, filters, and sort order as a named Saved_View using a name of 1 to 100 characters, THE Backend SHALL persist the Saved_View scoped to the requesting user and the table key, independent of any Store, and THE Frontend SHALL list it for later selection.
12. THE Backend SHALL persist each Saved_View and each Column_Configuration keyed by the pair (user identifier, table key) and store-independent, such that a saved view or column configuration follows the user across Stores and is never readable by another user.
13. IF an operator saves a Saved_View with an empty name, a name exceeding 100 characters, or a name identical to an existing Saved_View for the same user and table key, THEN THE Frontend SHALL reject the save, retain the existing Saved_Views unchanged, and present an indication of the naming conflict or limit violation.
14. WHEN an operator selects a previously saved Saved_View, THE Shared_Data_Table SHALL apply that view's column configuration, filters, and sort order.
15. WHERE a host page supplies no records for the current filters, THE Shared_Data_Table SHALL render an empty-state view rather than an error state.

### Requirement 3: Standardize Data Fetching on React Query

**User Story:** As a frontend developer, I want a single data-fetching pattern built on react-query, so that loading, error, caching, and refetch behavior is consistent instead of hand-rolled per page.

The Backend wraps responses in `ApiResponse` and the Frontend wraps calls in `api.ts`. No `QueryClientProvider` is currently mounted. Design MUST define the query-key convention, the default cache/stale settings, and the migration approach for existing pages.

#### Acceptance Criteria

1. THE Frontend SHALL mount a single Query_Client instance at the application root so that query and mutation hooks are available to all pages.
2. WHEN a page reads Backend data through the Data_Fetching_Layer, THE Data_Fetching_Layer SHALL expose a loading state that is true while the request is in flight, an error state, and the returned data unwrapped from the ApiResponse envelope.
3. WHEN a page performs a write through the Data_Fetching_Layer and the write succeeds, THE Data_Fetching_Layer SHALL provide a mechanism to refresh the affected cached reads so the displayed data reflects the write without a full page reload.
4. IF a Backend request issued through the Data_Fetching_Layer fails, THEN THE Data_Fetching_Layer SHALL surface an error state to the page that includes a human-readable message derived from the ApiResponse envelope and SHALL retain the last successfully cached data rather than clearing it.
5. WHERE a read or idempotent GET request issued through the Data_Fetching_Layer fails due to a transient network error or timeout, THE Data_Fetching_Layer SHALL automatically re-issue the failed request up to 3 times.
6. IF a non-idempotent write (mutation) issued through the Data_Fetching_Layer fails, THEN THE Data_Fetching_Layer SHALL NOT automatically re-issue the mutation and SHALL instead expose a manual retry mechanism that re-issues the mutation only when invoked by the operator.
7. WHEN a page is migrated to the Data_Fetching_Layer, THE migrated page SHALL preserve its existing store-scoping, loading, empty-state, and error-handling behavior with no observable change to those behaviors.
8. THE Data_Fetching_Layer SHALL assign each read a Query_Key that is unique per Backend resource and per store scope, expressed as a tuple/array of `[resource, storeId, ...params]` (for example, `['campaigns', storeId, filters]`), so that data for different resources or stores does not share a cache entry.
9. THE Data_Fetching_Layer SHALL apply a default stale time of 30 seconds and a default `gcTime` (garbage-collection / cache retention) of 300 seconds to reads that do not specify their own values.
10. THE migration to the Data_Fetching_Layer SHALL proceed incrementally on a page-by-page basis without a big-bang rewrite, and WHILE the migration is in progress THE Data_Fetching_Layer SHALL coexist with not-yet-migrated pages that still use hand-rolled `useEffect`-based fetching.

### Requirement 4: Multi-Leg Shipment Transport Path

**User Story:** As a logistics operator, I want to record an ordered set of transport legs for a shipment, each with its own carrier, dates, and costs, so that I can manage the full multi-segment cross-border transport path instead of a single flat shipment record.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record an ordered list of 1 to 20 Shipment_Legs, each with a leg_type, a sequence number, an assigned Carrier, a departure date, an arrival date, and a leg cost that is a monetary value greater than or equal to 0.00 and less than or equal to 999,999,999.99.
2. THE Logistics_Module SHALL at minimum support recording a first leg (头程) and a last leg (尾程) for a Shipment, and SHALL permit additional intermediate legs (for example, factory-to-forwarder, forwarder-to-port, ocean or air freight, and customs) within the 1-to-20 leg bound.
3. WHEN an operator records or updates a Shipment_Leg for a Shipment with all required fields (leg_type, sequence number, Carrier, departure date, arrival date, and leg cost) present and valid, THE Backend SHALL persist the Shipment_Leg associated with that Shipment and return the saved Shipment_Leg including its leg_type, sequence number, assigned Carrier, departure date, arrival date, and leg cost.
4. WHEN an operator opens a Shipment's detail view, THE Frontend SHALL display the Shipment's Shipment_Legs ordered by sequence number, each showing its leg_type, assigned Carrier, departure date, arrival date, and leg cost.
5. IF a Shipment_Leg is submitted with an arrival date earlier than its departure date, THEN THE Backend SHALL reject the request, leave any previously persisted Shipment_Leg data unchanged, and return a validation error indicating that the arrival date must be on or after the departure date.
6. IF a Shipment_Leg is submitted with a missing leg_type, missing sequence number, missing Carrier, missing departure date, missing arrival date, or a leg cost outside the range 0.00 to 999,999,999.99, THEN THE Backend SHALL reject the request, leave any previously persisted Shipment_Leg data unchanged, and return a validation error identifying each invalid or missing field.
7. IF an operator submits Shipment_Legs that would cause a Shipment to exceed 20 legs, THEN THE Backend SHALL reject the request, leave any previously persisted Shipment_Leg data unchanged, and return a validation error indicating the leg-count limit.
8. WHERE a Shipment has no recorded Shipment_Leg, THE Frontend SHALL display an empty-leg state for that Shipment rather than an error indication.

### Requirement 5: Carton Specifications

**User Story:** As a logistics operator, I want to record box/carton specifications for a shipment, so that I can plan volume, weight, and per-box quantities accurately.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record between 1 and 100 Carton_Spec entries, each with box length, box width, and box height in centimeters (each from 0.1 to 1000.0), box weight in kilograms (from 0.01 to 10000.00), units per box as an integer (from 1 to 1,000,000), and box count as an integer (from 1 to 1,000,000).
2. WHEN an operator saves a Carton_Spec for a Shipment, THE Backend SHALL persist the Carton_Spec associated with that Shipment and return the saved Carton_Spec including its generated identifier and all recorded field values.
3. WHEN a Shipment's Carton_Spec entries are displayed, THE Frontend SHALL display the total box count as the sum of box count across all Carton_Spec entries and the total unit quantity as the sum of (units per box multiplied by box count) across all Carton_Spec entries.
4. WHEN a Shipment with zero Carton_Spec entries is displayed, THE Frontend SHALL display a total box count of 0 and a total unit quantity of 0.
5. IF a Carton_Spec is submitted with a box count, units per box, box weight, box length, box width, or box height that is non-positive, non-numeric, or outside the bounds defined in criterion 1, THEN THE Backend SHALL reject the request, leave any previously persisted Carton_Spec entries unchanged, and return a validation error identifying each invalid field and the reason it is invalid.

### Requirement 6: Carriers as Managed Entities

**User Story:** As a logistics operator, I want carriers managed as reusable entities, so that I can assign a consistent carrier to shipment legs and reference its service type.

#### Acceptance Criteria

1. THE Logistics_Module SHALL maintain Carrier records, each with a carrier name of 1 to 200 characters and a service type of 1 to 100 characters.
2. IF an operator creates or updates a Carrier with a carrier name or service type that is empty, missing, or exceeds its maximum length, THEN THE Backend SHALL reject the request, return an error indicating which field is invalid, and persist no changes to the Carrier record.
3. WHEN an operator assigns a Carrier to a Shipment_Leg, THE Backend SHALL associate the Shipment_Leg with the referenced Carrier and persist the association.
4. WHEN an operator lists Carriers, THE Backend SHALL return all available Carrier records as JSON, including each record's carrier name and service type.
5. WHEN an operator lists Carriers and no Carrier records exist, THE Backend SHALL return an empty JSON array.
6. IF an operator assigns a Carrier reference that does not correspond to an existing Carrier, THEN THE Backend SHALL reject the request, return an error indicating the Carrier was not found, and leave the Shipment_Leg's existing Carrier association unchanged.

### Requirement 7: Customs Clearance Tracking

**User Story:** As a logistics operator, I want to track customs clearance for a shipment, so that I can monitor declaration status and account for duties and taxes.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record a Customs_Clearance with a clearance status that is exactly one of not-started, declared, in-review, cleared, or held; a declaration reference of 1 to 100 characters; and a duties-and-taxes amount between 0.00 and 999,999,999.99.
2. WHEN an operator updates the Customs_Clearance status of a Shipment with a status value that is one of the permitted clearance status values, THE Backend SHALL persist the updated status within 3 seconds and return the saved Customs_Clearance.
3. IF an operator updates the Customs_Clearance status of a Shipment with a status value that is not one of the permitted clearance status values, THEN THE Backend SHALL reject the update, retain the previously persisted Customs_Clearance unchanged, and return an error indicating the status value is invalid.
4. IF persisting an updated Customs_Clearance status fails, THEN THE Backend SHALL retain the previously persisted Customs_Clearance unchanged and return an error indicating the update could not be saved.
5. WHEN a Shipment's Customs_Clearance is displayed, THE Frontend SHALL display the clearance status, the declaration reference, and the duties-and-taxes amount.
6. WHERE a Shipment has no Customs_Clearance record, THE Frontend SHALL display a not-started clearance state for that Shipment.

### Requirement 8: Shipment Tracking Trajectory

**User Story:** As a logistics operator, I want a chronological tracking trajectory for a shipment, so that I can see where it is and what has happened to it over time.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record up to 1,000 Tracking_Event entries, where each entry has a timestamp recorded with date and time-of-day precision, a location or status description of 1 to 500 characters, and a reference to the associated Shipment_Leg.
2. IF a Tracking_Event is recorded without a timestamp, or with a location or status description that is empty or exceeds 500 characters, THEN THE Logistics_Module SHALL reject the entry, return an error indicating the invalid field, and leave the Shipment's existing Tracking_Event entries unchanged.
3. WHERE a Tracking_Event is not associated with any Shipment_Leg, THE Logistics_Module SHALL record the entry with an unset Shipment_Leg reference.
4. WHEN an operator opens a Shipment's tracking view, THE Backend SHALL return the Shipment's Tracking_Event entries ordered from most recent timestamp to oldest timestamp.
5. WHEN two or more Tracking_Event entries share the same timestamp, THE Backend SHALL order those entries from most recently recorded to least recently recorded.
6. IF an operator opens the tracking view for a Shipment that does not exist or that the operator is not authorized to view, THEN THE Backend SHALL return an error indicating the Shipment is unavailable and SHALL NOT return any Tracking_Event entries.
7. WHEN the Tracking_Event entries are displayed, THE Frontend SHALL render them as a chronological trajectory ordered from most recent to oldest.
8. WHERE a Shipment has zero Tracking_Event entries, THE Frontend SHALL display an empty-trajectory state rather than an error.

### Requirement 9: Shipment Exceptions

**User Story:** As a logistics operator, I want to raise and resolve exceptions against a shipment, so that delays, damage, and customs holds are tracked to resolution.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment_Exception to be recorded against a Shipment with an exception type that is one of delay, damage, or customs hold, a description of 1 to 1000 characters, and a resolution state that is one of open or resolved.
2. WHEN an operator records a Shipment_Exception with a valid exception type and a description of 1 to 1000 characters, THE Backend SHALL persist the Shipment_Exception associated with the Shipment in the open state and return the saved Shipment_Exception.
3. IF an operator submits a Shipment_Exception with a missing or unrecognized exception type, or a description that is empty or exceeds 1000 characters, THEN THE Backend SHALL reject the request without persisting any Shipment_Exception and return an error indication identifying the invalid field.
4. WHEN an operator marks an open Shipment_Exception as resolved, THE Backend SHALL persist the resolved state and the resolving Audit_Context, and return the updated Shipment_Exception.
5. IF an operator attempts to mark a Shipment_Exception as resolved when it is already in the resolved state, THEN THE Backend SHALL reject the request without altering the stored Shipment_Exception and return an error indication that the exception is already resolved.
6. WHILE a Shipment has one or more Shipment_Exception entries in the open state, THE Frontend SHALL display an exception indicator for that Shipment.
7. WHEN an operator lists Shipment_Exception entries for the Active_Store, THE Backend SHALL return only the Shipment_Exception entries belonging to Shipments of the Active_Store.

### Requirement 10: Shipment Cost Chain

**User Story:** As a logistics operator, I want an itemized cost chain for a shipment, so that I can see the total landed logistics cost across legs, customs, and handling.

#### Acceptance Criteria

1. THE Logistics_Module SHALL compose a Shipment's Cost_Chain from the sum of its Shipment_Leg costs, its Customs_Clearance duties-and-taxes amount, and the sum of its Handling_Cost lines (as defined in Requirement 18).
2. WHEN a Shipment's Cost_Chain is requested for an existing Shipment, THE Backend SHALL return each itemized cost component (Shipment_Leg costs, Customs_Clearance duties-and-taxes, and Handling_Cost lines) and the aggregated total landed logistics cost, where the aggregated total equals the arithmetic sum of all included components expressed in the Shipment's Reporting_Currency.
3. WHEN the Cost_Chain is displayed, THE Frontend SHALL display each cost component and the aggregated total landed logistics cost.
4. WHERE a Shipment cost component is recorded in a currency other than the Shipment's Reporting_Currency, THE Backend SHALL convert that component to the Reporting_Currency using the exchange rate recorded on the component when present, or otherwise the documented Store Reporting_Currency rate, applying the exchange rate effective on the component's recorded cost date, and SHALL retain the original currency amount and original currency code alongside the component.
5. WHEN the Backend converts a cost component into the Reporting_Currency, THE Backend SHALL round the converted amount to 2 decimal places using round-half-up.
6. WHERE a cost component has no recorded amount, THE Backend SHALL treat that component as a value of zero when computing the aggregated total landed logistics cost.
7. THE Logistics_Module SHALL compute the Cost_Chain at the Shipment level only, and SKU-level cost allocation SHALL be out of scope.
8. IF a Cost_Chain is requested for a Shipment that does not exist or that the requester is not authorized to access, THEN THE Backend SHALL reject the request, return no cost data, and return a response indicating the shipment was not found or access was denied.

### Requirement 11: Command Center Audit Navigation Fix

**User Story:** As an operator, I want the Command Center audit quick-entries to open the audit page, so that the links do not lead to a dead route.

`CommandCenterPage.tsx` links to `/audit`, while the registered route is `/audit-rollback`. Design MUST confirm every audit-targeting link in the Command Center.

#### Acceptance Criteria

1. WHEN an operator activates an audit quick-entry in the Command Center by mouse click or keyboard (Enter or Space) activation, THE Frontend SHALL navigate to the registered `/audit-rollback` route.
2. WHEN an operator activates the Command Center "view all recent operations" audit link by mouse click or keyboard (Enter or Space) activation, THE Frontend SHALL navigate to the registered `/audit-rollback` route.
3. THE Frontend SHALL NOT contain any navigation target equal to `/audit` that resolves to no registered route, such that a route-resolution test over all Command Center audit-targeting links returns zero matches for `/audit`.
4. THE Frontend SHALL ensure that every audit-targeting link rendered in the Command Center resolves to a route registered in the application router, with a count of unresolved audit-targeting links equal to 0.

### Requirement 12: Real Audit Context for Advertising Operations

**User Story:** As a compliance reviewer, I want advertising operations attributed to the acting user, so that the audit trail reflects who performed each action instead of a literal "system" placeholder.

`GoalController`, `KeywordController`, `RecommendationController`, and `SearchTermController` hardcode `userId = "system"` with a `// TODO: get from auth context`. Design MUST confirm the full set of affected operations and how each propagates the acting user to its service and audit record.

#### Acceptance Criteria

1. WHEN an authenticated user invokes a goal, keyword, recommendation, or search-term operation that records an acting user, THE Backend SHALL resolve the acting user as the unique user identifier from the Audit_Context rather than a literal "system" value, and SHALL complete this resolution before invoking the corresponding service operation.
2. WHEN the Backend records or audits one of these operations for an authenticated request, THE Backend SHALL store the resolved Audit_Context unique user identifier as the acting-user attribution on the resulting Audit_Trail record.
3. IF the Audit_Context cannot resolve an authenticated user for an operation that requires a permission, THEN THE Backend SHALL reject the request with an authorization error indicating the acting user could not be determined, SHALL NOT execute the operation under any placeholder or default identity, and SHALL leave all target data unchanged.
4. THE Backend SHALL NOT pass the literal string "system", nor any other hardcoded placeholder identity, as the acting user for goal, keyword, recommendation, or search-term operations initiated by an authenticated request.
5. WHERE a goal, keyword, recommendation, or search-term operation legitimately runs without an interactive user (for example, a scheduled or background trigger), THE Backend SHALL record a reserved non-interactive actor identifier in the Audit_Trail that does not match any authenticated user identifier.

### Requirement 13: Real Platform Connection Test

**User Story:** As an administrator, I want testing a platform connection to validate the stored credentials against the platform, so that the result reflects real connectivity rather than a no-op.

`ApiSyncServiceImpl.testPlatformConnection(String id)` only logs and contains a `// TODO: integrate with actual platform API`, while `testPlatformByKey` already delegates to `PlatformConnector.test(...)`. The frontend `ApiConnectionsPage.tsx` calls `testPlatformConnection(platform)` keyed by platform key. Design MUST confirm that both entry points (by platform key and by connection identifier) converge on the real platform connector test rather than forcing a connection-identifier-only contract.

#### Acceptance Criteria

1. WHEN an administrator tests a Platform_Connection through either entry point (by platform key or by connection identifier), THE Backend SHALL validate the connection's stored credentials against the external platform through the platform connector and return the connectivity outcome within 30 seconds.
2. WHEN a Platform_Connection_Test succeeds, THE Backend SHALL return a success result with a human-readable message indicating that connectivity to the external platform was confirmed.
3. IF a Platform_Connection_Test fails because the credentials are rejected or incomplete, THEN THE Backend SHALL return a failure result with a human-readable message describing the reason and SHALL leave the stored credentials unchanged.
4. IF a Platform_Connection_Test is requested for a platform key or connection identifier that does not correspond to an existing Platform_Connection, THEN THE Backend SHALL return an error indicating the connection was not found.
5. IF the platform connector cannot be reached or does not return a result within the 30-second limit, THEN THE Backend SHALL return a failure result with a human-readable message indicating the platform is unreachable and SHALL leave the stored credentials unchanged.
6. THE Backend SHALL NOT report a Platform_Connection_Test as successful without obtaining a success result from the platform connector.

### Requirement 14: Real Insight Agent Implementation

**User Story:** As an advertising operator, I want the Insight Agent to return real AI-generated insights when AI is configured, so that I receive genuine analysis rather than a fixed deterministic stub.

`InsightAgentServiceImpl` returns `generatedBy:"stub"` deterministic content when AI is not enabled or the AI call fails. Design MUST confirm AI provider configuration and the intended behavior distinction between AI-generated and degraded responses.

#### Acceptance Criteria

1. WHERE the AI_Provider is enabled, WHEN an operator submits an Insight_Agent query for the Active_Store, THE Backend SHALL request analysis from the AI_Provider and SHALL return AI-generated insights containing at most 10 recommended actions within 30 seconds of query submission.
2. WHERE the AI_Provider is enabled and the store has no data or no action is appropriate, WHEN the Insight_Agent produces an AI-generated result, THE Backend SHALL return zero recommended actions together with a human-readable explanation of why no actions were produced.
3. THE Insight_Agent result SHALL conform to a defined output structure that the Frontend can render, comprising an insights list, a recommended-actions list, and a source marker.
4. IF the AI_Provider returns output that does not conform to the defined output structure, THEN THE Backend SHALL treat the result as a degraded result.
5. WHEN the Insight_Agent returns an AI-generated result, THE Backend SHALL mark the result with a source indicator that identifies it as AI-generated and is distinguishable from the indicator used for a degraded result.
6. IF the AI_Provider is enabled but the analysis request fails or does not complete within 30 seconds, THEN THE Backend SHALL return a degraded result marked as degraded, SHALL record the failure reason, and SHALL retain the operator-submitted query input.
7. WHERE the AI_Provider is not enabled, WHEN an operator submits an Insight_Agent query for the Active_Store, THE Backend SHALL return a result marked as degraded rather than marked as AI-generated.
8. THE Insight_Agent SHALL NOT include any secret or credential value in prompts sent to the AI_Provider or in the result returned to the operator.
9. WHEN the Insight_Agent produces a result, THE Backend SHALL persist the result to the saved-insights history.
10. IF persistence of the result to the saved-insights history fails, THEN THE Backend SHALL return the produced result to the operator and SHALL record the persistence failure reason.

### Requirement 15: Protect and Externalize Amazon Ads LWA Credentials (Priority: P0 Security)

**User Story:** As a security engineer, I want the application's Amazon Ads LWA OAuth client credentials externalized to environment variables and never committed, so that a real client secret is not exposed in source control.

`application.yml` currently hardcodes a real `adpilot.amazon-ads.client-id` and `client-secret` in committed source, even though the file's own comment requires them to be left blank and supplied via `ADPILOT_AMAZON_ADS_CLIENT_ID` and `ADPILOT_AMAZON_ADS_CLIENT_SECRET`. This is distinct from `core-platform-completion`, which encrypts per-Store Platform_Connection credentials via `CryptoUtil` and does not cover the application's own LWA client secret. Because the value was exposed in source history, secret rotation is required as part of this work.

#### Acceptance Criteria

1. THE committed application configuration SHALL contain an empty default value for `adpilot.amazon-ads.client-id` and an empty default value for `adpilot.amazon-ads.client-secret`, and SHALL NOT contain a non-empty literal client-id or client-secret value.
2. THE Backend SHALL source the LWA_Credentials from the `ADPILOT_AMAZON_ADS_CLIENT_ID` and `ADPILOT_AMAZON_ADS_CLIENT_SECRET` environment variables, falling back to a blank default when an environment variable is not set.
3. THE Backend SHALL NOT write the LWA client secret to any log output and SHALL NOT include the LWA client secret in any API response body.
4. IF an operation that requires the LWA_Credentials is invoked WHILE the client-id or client-secret is blank, THEN THE Backend SHALL reject the operation with a 4xx error indicating the Amazon Ads credentials are not configured, and SHALL NOT raise an unhandled server error.
5. THE LWA client secret previously committed to source SHALL be rotated, such that the value present in source history is no longer a valid credential.

### Requirement 16: FBA Shipment Core Fields

**User Story:** As a logistics operator, I want to record core Amazon FBA attributes on a shipment, so that I can identify the FBA shipment, its status, its destination fulfillment center, and its line items.

Box-label (箱唛) printing and linkage to purchase orders, replenishment plans, and inbound receiving are explicitly out of scope for this requirement.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record an FBA shipment identifier of 1 to 100 characters, an Amazon shipment status, a destination Fulfillment_Center code of 1 to 50 characters, and 0 or more Shipment_Line_Items.
2. THE Logistics_Module SHALL allow each Shipment_Line_Item to record a SKU of 1 to 100 characters, an ASIN of 1 to 20 characters, an MSKU of 1 to 100 characters, and a quantity that is an integer from 1 to 1,000,000.
3. WHEN an operator saves the FBA_Shipment_Fields for a Shipment with all provided values valid, THE Backend SHALL persist the FBA_Shipment_Fields associated with that Shipment and return the saved values.
4. WHEN an operator opens a Shipment's detail view, THE Frontend SHALL display the FBA shipment identifier, the Amazon shipment status, the destination Fulfillment_Center code, and the Shipment_Line_Items with their quantities.
5. IF an operator saves the FBA_Shipment_Fields with an FBA shipment identifier, Fulfillment_Center code, SKU, ASIN, or MSKU that is empty or exceeds its maximum length, or a line-item quantity outside the range 1 to 1,000,000, THEN THE Backend SHALL reject the request, leave any previously persisted FBA_Shipment_Fields unchanged, and return a validation error identifying each invalid field.
6. WHEN an operator lists or reads FBA_Shipment_Fields, THE Backend SHALL return only the FBA_Shipment_Fields belonging to Shipments the requester is authorized to access for the Active_Store.

### Requirement 17: Tenant and Store Isolation for Logistics Entities

**User Story:** As a security engineer, I want all new logistics entities isolated by tenant and store, so that cross-tenant and cross-store reads are impossible.

#### Acceptance Criteria

1. THE Logistics_Module SHALL scope each Carrier to an Organization, such that a Carrier is shared across all Stores of the owning Organization and is not readable by any other Organization.
2. THE Logistics_Module SHALL scope each Shipment and its child records (Shipment_Leg, Carton_Spec, Customs_Clearance, Tracking_Event, Shipment_Exception, and Handling_Cost) to the owning Store.
3. WHEN a requester lists or reads any Carrier, Shipment, Shipment_Leg, Carton_Spec, Customs_Clearance, Tracking_Event, Shipment_Exception, or Handling_Cost, THE Backend SHALL return only the records the requester is authorized to access for the Active_Store or their Organization, as resolved by the Active_Store_Scope.
4. IF a requester requests a Carrier, Shipment, or Shipment child record outside the requester's Active_Store_Scope, THEN THE Backend SHALL reject the request, return no record data, and return a response indicating the record was not found or access was denied.
5. THE Logistics_Module SHALL scope each Saved_View to the requesting user independent of any Store, such that no user can read another user's Saved_View.

### Requirement 18: Handling Cost Management

**User Story:** As a logistics operator, I want to manage handling cost lines on a shipment, so that miscellaneous handling charges are itemized and included in the cost chain.

#### Acceptance Criteria

1. THE Logistics_Module SHALL allow a Shipment to record Handling_Cost lines, each with an amount that is a monetary value greater than or equal to 0.00 and less than or equal to 999,999,999.99, a currency code of exactly 3 characters, and a description or category of 1 to 200 characters.
2. WHEN an operator creates, updates, or deletes a Handling_Cost line on a Shipment with all provided values valid, THE Backend SHALL persist the change associated with that Shipment and return the resulting Handling_Cost state.
3. WHEN an operator reads a Shipment's Handling_Cost lines, THE Backend SHALL return each Handling_Cost line with its amount, currency code, and description or category.
4. IF an operator submits a Handling_Cost line with an amount outside the range 0.00 to 999,999,999.99, a currency code that is not exactly 3 characters, or a description or category that is empty or exceeds 200 characters, THEN THE Backend SHALL reject the request, leave any previously persisted Handling_Cost lines unchanged, and return a validation error identifying each invalid field.
5. THE Cost_Chain defined in Requirement 10 SHALL aggregate the Shipment's Handling_Cost lines.

### Requirement 19: Logistics Schema Ownership

**User Story:** As a backend developer, I want this spec's new logistics tables added to the single source-of-truth schema, so that the database can be provisioned consistently without runtime migrations.

#### Acceptance Criteria

1. THE Backend SHALL define the schema for this spec's new logistics tables (Shipment_Leg, Carton_Spec, Carrier, Customs_Clearance, Tracking_Event, Shipment_Exception, Handling_Cost, FBA_Shipment_Fields, Shipment_Line_Item, and Saved_View) in `backend-java/db/schema.sql`.
2. THE Backend SHALL NOT rely on runtime migrations or schema auto-generation to create this spec's new logistics tables, consistent with the disabled Flyway and `ddl-auto: none` configuration.
3. THE new logistics tables defined in `schema.sql` SHALL include the tenant and store scoping columns required to enforce the isolation defined in Requirement 17.
