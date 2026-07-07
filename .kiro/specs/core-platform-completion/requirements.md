# Requirements Document

## Introduction

AdPilot AI is a cross-border e-commerce multi-platform operations ERP. The current system can store and validate platform credentials, defines a full RBAC model (users, roles, permissions, data scopes), and provides CRUD modules across orders, advertising, inventory, finance, and listings. However, three structural gaps prevent it from operating as a live system: platform credentials are never used to actually pull data, defined permissions are not enforced at the API or UI layer, and there is no scheduler to run work automatically. Most analytic modules currently run on manually-imported CSV or placeholder data.

This feature is a phased roadmap to "make the system live". It is organized by capability area, and every requirement group is tagged with a phase (P0–P3) so the design and task phases can sequence delivery. The phases progress from establishing real data flow and access control (P0), through automation and multi-store breadth (P1), through Amazon integration, finance, alerts, and account security (P2), to approval workflows and AI/automation write-back to live platforms (P3).

The guiding end-to-end target data flow is:

> encrypted credential → scheduled sync job pulls [orders / products / inventory / ad reports] → data-quality validation + multi-store / multi-currency normalization → drives [profit calculation / inventory health / keyword analysis / replenishment] → AI recommendations + automation rules → one-click apply / auto-execute → write back to platform → audit + alerts + reports.

Acceptance criteria are written in EARS format and kept technology-neutral (describing what the system must do, not how), while remaining aware of the existing implementation (MyBatis-Plus + JPA on MySQL 8, Redis, Spring Boot, React/TS frontend, existing tables such as `platform_connections`, `api_sync_jobs`, `api_sync_logs`, `sync_schedules`, `external_entity_mappings`, `data_scopes`, `approval_policies`, `login_logs`).

## Glossary

- **System**: The AdPilot AI ERP application as a whole, comprising the backend services and the frontend application.
- **Sync_Service**: The backend component responsible for pulling data from external platforms into the internal database.
- **Scheduler**: The backend component that triggers recurring jobs (data sync, replenishment generation, scheduled reports) according to configured schedules.
- **Permission_Service**: The backend component that determines whether a user is authorized to perform a requested operation.
- **Data_Scope_Service**: The backend component that restricts the rows a user may read or write based on the data scope assigned to their roles.
- **Frontend**: The React/TypeScript client application.
- **Sync Job**: A unit of work that pulls a defined entity type (orders, products, inventory, advertising reports) from one platform connection into the internal database, recording its status, counts, and logs.
- **Sync Schedule**: A configuration record that defines when a sync job runs automatically, including a recurrence expression and an enabled flag.
- **Data Scope**: A constraint attached to a role that limits the data a user may access along one of these dimensions: all company, department, own (records the user owns), assigned store, or assigned product.
- **Effective Data Scope**: The data scope that results from combining all of a user's roles. When a user holds multiple roles, the effective data scope is the broadest applicable access across those roles, determined by the precedence: all-company (broadest) → department → assigned-store / assigned-product → own (narrowest).
- **Sync Watermark**: A per-Store, per-entity-type marker recording the point (for example, a timestamp or change cursor) of the last successfully synced record, used to retrieve only records created or changed since the previous successful sync.
- **Store**: An internal record representing a single selling channel instance on a single marketplace (for example, a Shopify store, or an Amazon US storefront).
- **Marketplace**: A regional selling destination on a platform (for example, Amazon US, Amazon UK, Amazon DE), each with its own currency.
- **Platform**: An external commerce or advertising provider the System integrates with (WooCommerce, Shopify, Amazon SP-API, Amazon Ads, TikTok Shop).
- **Platform Connection**: A stored, encrypted credential plus configuration that links a Store to a Platform.
- **Seller Account**: A single set of platform credentials that may grant access to multiple marketplaces or stores.
- **Upsert**: An insert-or-update operation keyed on a stable external identifier so that repeated syncs do not create duplicates.
- **Idempotent**: A property of an operation whereby running it multiple times with the same input produces the same resulting state as running it once.
- **BuyBox**: The featured offer position on an Amazon product detail page that wins the default "Add to Cart" action; losing it materially reduces sales.
- **ACoS**: Advertising Cost of Sales, the ratio of advertising spend to advertising-attributed sales, expressed as a percentage.
- **Reconciliation**: The process of matching platform-reported settlement amounts and fees against internally computed expected amounts to confirm correctness and surface discrepancies.
- **Exchange Rate**: A conversion factor between two currencies as of a given date, used to normalize amounts to a reporting currency.
- **Alert**: A System-generated notification triggered by a business condition (for example, stockout, ACoS over threshold, BuyBox loss, negative review).
- **Approval Policy**: A configuration that defines which users must approve an action, the value thresholds that require approval, and the number of approval levels.

---

## Requirements

### Capability Area 1: Platform Data Sync (P0)

#### Requirement 1.1: WooCommerce and Shopify Data Pull

**User Story:** As an operations user, I want the System to pull real orders and products from WooCommerce and Shopify, so that the modules I work in reflect live store data instead of placeholder or CSV data.

#### Acceptance Criteria

1. WHERE a Store has an active Platform Connection for WooCommerce or Shopify, THE Sync_Service SHALL retrieve orders and products from that platform using the connection's stored credentials.
2. WHEN a user triggers an on-demand sync for a Store, THE Sync_Service SHALL create a sync job and begin retrieving the requested entity type for that Store.
3. WHEN the Sync_Service retrieves an external order or product, THE Sync_Service SHALL map the external record to the corresponding internal entity fields.
4. WHEN the Sync_Service maps an external record, THE Sync_Service SHALL associate the resulting internal record with the Store that owns the originating Platform Connection.
5. IF the platform credentials are rejected by the external platform, THEN THE Sync_Service SHALL mark the sync job as failed and record the failure reason.
6. WHEN a Store has been previously synced for an entity type, THE Sync_Service SHALL retrieve only the records created or changed since the last successful Sync Watermark for that Store and entity type.
7. WHERE no prior Sync Watermark exists for a Store and entity type, or a full resync is explicitly requested, THE Sync_Service SHALL perform a full retrieval of that entity type for that Store.
8. WHEN a sync job completes successfully, THE Sync_Service SHALL update the Sync Watermark for that Store and entity type to reflect the most recent record processed.

#### Requirement 1.2: Idempotent Upserts

**User Story:** As an operations user, I want repeated syncs to update existing records instead of duplicating them, so that my data stays accurate across multiple sync runs.

#### Acceptance Criteria

1. WHEN the Sync_Service processes an external record whose external identifier already maps to an internal record, THE Sync_Service SHALL update the existing internal record rather than create a new one.
2. WHEN the Sync_Service processes an external record whose external identifier has no existing internal mapping, THE Sync_Service SHALL create a new internal record and store the mapping between the external identifier and the internal record.
3. WHEN the same sync job input is processed more than once, THE Sync_Service SHALL produce the same resulting set of internal records as processing it once (idempotence).
4. WHEN an external record that previously mapped to an internal record is reported as deleted or cancelled by the platform, THE Sync_Service SHALL mark the corresponding internal record's status accordingly (for example, cancelled or inactive) rather than physically delete the internal record.

#### Requirement 1.3: Sync Job Status and Logging

**User Story:** As an operations user, I want to see the status, counts, and logs of each sync, so that I can confirm syncs succeeded and diagnose failures.

#### Acceptance Criteria

1. WHEN a sync job starts, THE Sync_Service SHALL record the job status as running and record the start time.
2. WHILE a sync job is running, THE Sync_Service SHALL record the count of processed records and the count of failed records.
3. WHEN a sync job finishes successfully, THE Sync_Service SHALL record the job status as completed and record the completion time.
4. WHEN a sync job encounters a record-level error, THE Sync_Service SHALL record a log entry containing the error detail and continue processing remaining records.
5. WHEN a user requests the sync history for a Store, THE System SHALL return the sync jobs for that Store ordered by most recent first.
6. WHILE a sync job for a given Store and entity type is running, THE Sync_Service SHALL NOT start a second concurrent sync job for the same Store and entity type, and SHALL either reject the new request or queue it for execution after the running job completes.

#### Requirement 1.4: Data-Quality Validation on Mapping

**User Story:** As an operations user, I want incoming external records validated before they are written, so that malformed or incomplete data does not corrupt the internal modules.

#### Acceptance Criteria

1. WHEN the Sync_Service maps an external record, THE Sync_Service SHALL validate the record against the configured required-field rules and type or format rules for that entity type.
2. IF a record fails validation, THEN THE Sync_Service SHALL record a data-quality error for that record and exclude the record from upsert.
3. WHEN a record is excluded from upsert due to a data-quality error, THE Sync_Service SHALL continue processing the remaining records in the sync job.

### Capability Area 2: Interface-Level Permission Enforcement (P0)

#### Requirement 2.1: API Authorization

**User Story:** As a security administrator, I want every API to enforce the caller's permissions, so that users cannot perform actions they are not authorized for.

#### Acceptance Criteria

1. WHEN an authenticated request is received for an operation that requires a permission, THE Permission_Service SHALL verify that the requesting user holds the required permission before the operation executes.
2. IF the requesting user does not hold the required permission, THEN THE System SHALL reject the request with an HTTP 403 status and an error message.
3. IF a request is received without valid authentication for an operation that requires authentication, THEN THE System SHALL reject the request with an HTTP 401 status.
4. WHERE a user holds the super administrator role, THE Permission_Service SHALL authorize the request without requiring an explicit permission grant.
5. WHEN the Permission_Service authorizes or rejects a request, THE System SHALL record the user identity and the requested operation for audit purposes.

#### Requirement 2.2: Data Scope Enforcement on Read and Write

**User Story:** As a security administrator, I want API responses and writes restricted to the data a user is allowed to access, so that users only see and modify records within their assigned scope.

#### Acceptance Criteria

1. WHEN a user requests a list of records constrained by data scope, THE Data_Scope_Service SHALL return only the records permitted by the user's effective data scope.
2. IF a user requests a single record outside the user's effective data scope, THEN THE System SHALL reject the request with an HTTP 403 status.
3. IF a user attempts to create or modify a record outside the user's effective data scope, THEN THE System SHALL reject the request with an HTTP 403 status.
4. WHERE a user's effective data scope is assigned-store, THE Data_Scope_Service SHALL restrict accessible records to the Stores assigned to that user.
5. WHERE a user holds the super administrator role, THE Data_Scope_Service SHALL grant access to all records without applying data-scope restrictions.

### Capability Area 3: Frontend Permission-Driven UX (P0)

#### Requirement 3.1: Permission-Driven Navigation and Actions

**User Story:** As a logged-in user, I want the interface to show only the features I can use, so that I am not presented with actions I cannot perform.

#### Acceptance Criteria

1. WHEN the Frontend renders the navigation menu, THE Frontend SHALL display only the menu items for which the logged-in user holds the required permission.
2. WHERE the logged-in user lacks the permission required for an action control, THE Frontend SHALL hide or disable that action control.
3. WHEN the Frontend loads after login, THE Frontend SHALL retrieve the logged-in user's permission set from the System.
4. IF a user navigates directly to a route for which the user lacks the required permission, THEN THE Frontend SHALL prevent access to that route and display an access-denied indication.
5. WHEN the logged-in user's permission set changes, THE Frontend SHALL re-render navigation and action controls according to the updated permission set on the next permission fetch or navigation, without requiring the user to log out and log back in.

### Capability Area 4: Background Scheduling Engine (P1)

#### Requirement 4.1: Scheduled Job Execution

**User Story:** As an operations user, I want sync, replenishment generation, and report generation to run automatically on a schedule, so that data and outputs stay current without manual effort.

#### Acceptance Criteria

1. WHERE a Sync Schedule is enabled, THE Scheduler SHALL execute the associated job when the schedule's next run time is reached.
2. WHEN the Scheduler executes a scheduled job, THE Scheduler SHALL record the last run time and compute the next run time.
3. WHERE a Sync Schedule is disabled, THE Scheduler SHALL NOT execute the associated job.
4. WHEN a user creates or updates a Sync Schedule, THE System SHALL validate that the recurrence expression is well-formed before saving it.
5. IF a scheduled job execution fails, THEN THE Scheduler SHALL record the failure and compute the next run time so that subsequent executions are not blocked.

#### Requirement 4.2: Schedule Management and Manual Trigger

**User Story:** As an operations user, I want to enable, disable, inspect, and manually trigger schedules, so that I control automation and can run a job immediately when needed.

#### Acceptance Criteria

1. WHEN a user views a Sync Schedule, THE System SHALL display the schedule's enabled state, last run time, and next run time.
2. WHEN a user enables or disables a Sync Schedule, THE System SHALL persist the new enabled state.
3. WHEN a user manually triggers a scheduled job, THE Scheduler SHALL execute the job immediately without altering the configured recurrence.

### Capability Area 5: Multi-Store and Cross-Store Aggregation (P1)

#### Requirement 5.1: Cross-Store Aggregated View

**User Story:** As a manager overseeing several stores, I want an "All stores" view that aggregates dashboards, profit, and orders across the stores I can access, so that I can see overall performance and per-store breakdowns in one place.

#### Acceptance Criteria

1. WHEN a user selects the all-stores view, THE System SHALL aggregate dashboard, profit, and order metrics across the Stores the user is permitted to access.
2. WHEN the System returns aggregated metrics for the all-stores view, THE System SHALL also return the metrics grouped by Store.
3. THE System SHALL exclude from the all-stores aggregation any Store the user is not permitted to access.
4. WHERE aggregated metrics span Stores with different currencies, THE System SHALL convert amounts to a single reporting currency before aggregating.
5. WHERE no exchange rate is available for a currency encountered during all-stores aggregation, THE System SHALL present per-currency subtotals without forced conversion and flag the unconverted amounts, so that aggregation is not blocked by the absence of a rate. (Note: the exchange-rate mechanism is defined in P2, Requirement 9.2; this P1 aggregation depends on it only when conversion is possible and degrades gracefully otherwise.)

#### Requirement 5.2: Store Switcher, Grouping, and Default Store

**User Story:** As a user managing many stores, I want store grouping, a default store, and a searchable store switcher, so that I can navigate between stores quickly.

#### Acceptance Criteria

1. WHEN a user opens the store switcher, THE Frontend SHALL display only the Stores the user is permitted to access.
2. WHEN a user enters text in the store switcher search field, THE Frontend SHALL filter the displayed Stores to those whose name matches the entered text.
3. WHERE a user has configured a default Store, THE Frontend SHALL select that default Store on the next login.
4. WHERE Stores are assigned to groups, THE Frontend SHALL display Stores organized by their assigned group in the store switcher.

### Capability Area 6: Single-Account-Multi-Store Discovery (P1)

#### Requirement 6.1: Automatic Store Discovery from One Credential

**User Story:** As an administrator, I want one platform credential to automatically discover and import the multiple stores or marketplaces it covers, so that I do not have to create each store manually.

#### Acceptance Criteria

1. WHEN an administrator initiates discovery for a Seller Account credential, THE System SHALL retrieve the list of marketplaces or stores that the credential grants access to.
2. WHEN discovery returns a marketplace or store that has no corresponding internal Store, THE System SHALL create an internal Store for it linked to the originating Seller Account credential.
3. WHEN discovery returns a marketplace or store that already corresponds to an internal Store, THE System SHALL reuse the existing internal Store rather than create a duplicate.
4. WHEN the System creates Stores from discovery, THE System SHALL associate each created Store with the marketplace identifier reported by the platform.

### Capability Area 7: Unified Data Scope Query Layer (P1)

#### Requirement 7.1: Department and Own Data Scope Enforcement

**User Story:** As a security administrator, I want data scope enforced for the department and own dimensions through a single query layer, so that access control is consistent across all modules and not limited to store scope.

#### Acceptance Criteria

1. WHERE a user's effective data scope is department, THE Data_Scope_Service SHALL restrict accessible records to those belonging to the user's department.
2. WHERE a user's effective data scope is own, THE Data_Scope_Service SHALL restrict accessible records to those the user created or owns.
3. WHERE a user's effective data scope is all-company, THE Data_Scope_Service SHALL grant access to all records within the user's organization.
4. WHEN a user holds multiple roles with different data scopes, THE Data_Scope_Service SHALL grant the union of the records permitted by each role's data scope, resolving the effective data scope to the broadest applicable access according to the precedence all-company (broadest) → department → assigned-store / assigned-product → own (narrowest).
5. WHEN any module queries data-scoped records, THE Data_Scope_Service SHALL apply the same data scope rules through a shared query layer.

### Capability Area 8: Amazon SP-API and Ads Sync (P2)

#### Requirement 8.1: Amazon Real Data Sync with Signed Requests

**User Story:** As an operations user, I want the System to pull Amazon orders, inventory, and advertising reports, so that Amazon stores have the same live data coverage as WooCommerce and Shopify.

#### Acceptance Criteria

1. WHERE a Store has an active Amazon SP-API or Amazon Ads Platform Connection, THE Sync_Service SHALL retrieve orders, inventory, and advertising reports for that Store.
2. WHEN the Sync_Service issues a request to an Amazon platform, THE Sync_Service SHALL sign the request according to the platform's required signing scheme.
3. WHEN the Sync_Service retrieves an Amazon advertising report, THE Sync_Service SHALL map the report metrics to the internal advertising performance entities for the Store.
4. WHEN the Sync_Service processes Amazon records, THE Sync_Service SHALL apply idempotent upserts keyed on the Amazon external identifier.
5. IF an Amazon request is rejected for an expired or invalid token, THEN THE Sync_Service SHALL record the failure reason and mark the connection status as requiring re-authorization.

### Capability Area 9: Financial Reconciliation and Multi-Currency (P2)

#### Requirement 9.1: Settlement Reconciliation and Fee Aggregation

**User Story:** As a finance user, I want platform settlements reconciled against expected amounts with all fees aggregated, so that I can trust reported profit figures.

#### Acceptance Criteria

1. WHEN a settlement is synced for a Store, THE System SHALL aggregate the referral fee, fulfillment fee, and advertising spend associated with that settlement.
2. WHEN the System reconciles a settlement, THE System SHALL compare the platform-reported settlement amount against the internally computed expected amount.
3. IF a reconciled settlement amount differs from the internally computed expected amount beyond a configured tolerance, THEN THE System SHALL flag the settlement as a discrepancy.
4. WHEN the System computes profit for a Store, THE System SHALL include the aggregated fees in the profit calculation.

#### Requirement 9.2: Multi-Currency and VAT Normalization

**User Story:** As a finance user, I want amounts converted to a reporting currency and VAT accounted for, so that cross-marketplace figures are comparable.

#### Acceptance Criteria

1. WHERE a financial amount is recorded in a currency other than the reporting currency, THE System SHALL convert the amount using the exchange rate effective on the amount's transaction date.
2. WHEN the System converts an amount, THE System SHALL retain both the original currency amount and the converted reporting-currency amount.
3. WHERE a Store's marketplace is subject to VAT, THE System SHALL include the VAT component in the Store's financial calculations.
4. IF no exchange rate is available for a required currency and date, THEN THE System SHALL flag the affected amount as unconverted rather than apply an undefined rate.
5. WHEN the System requires an exchange rate for a currency pair and date, THE System SHALL obtain the rate from a configured exchange-rate source.
6. WHEN the System converts an amount, THE System SHALL retain the exchange rate value and the effective date of the rate used for that conversion.

### Capability Area 10: Alerts and Notifications (P2)

#### Requirement 10.1: Unified Alert and Notification Center

**User Story:** As an operations user, I want a single alert center plus Feishu push for critical business conditions, so that I am notified of stockouts, high ACoS, BuyBox loss, and negative reviews.

#### Acceptance Criteria

1. WHEN inventory for a product reaches or falls below its configured stockout threshold, THE System SHALL generate a stockout alert for the affected Store.
2. WHEN a campaign's ACoS exceeds its configured threshold, THE System SHALL generate an ACoS-over-threshold alert.
3. WHEN BuyBox loss is detected for a product, THE System SHALL generate a BuyBox-loss alert.
4. WHEN a negative review is detected for a product, THE System SHALL generate a negative-review alert.
5. WHEN an alert is generated, THE System SHALL display the alert in the alert center for users permitted to access the affected Store.
6. WHERE Feishu push is configured, THE System SHALL send the generated alert to the configured Feishu destination.
7. WHILE an alert condition remains active for a given Store, THE System SHALL NOT generate a duplicate alert for the same condition and Store, and SHALL update the existing open alert instead.
8. WHEN an alert condition is no longer met, THE System SHALL mark the corresponding alert as resolved.
9. IF a Feishu push fails, THEN THE System SHALL record the push failure and retain the alert in the alert center.

### Capability Area 11: Account Security (P2)

#### Requirement 11.1: Login Protection and Password Policy

**User Story:** As a security administrator, I want login failure lockout and a password strength policy, so that accounts are protected against brute-force and weak credentials.

#### Acceptance Criteria

1. WHEN the count of consecutive failed login attempts for an account reaches the configured limit, THE System SHALL lock the account for the configured lockout duration.
2. WHILE an account is locked, THE System SHALL reject login attempts for that account and record the rejection reason.
3. WHEN a user sets or changes a password, THE System SHALL reject the password if it does not satisfy the configured password strength policy.
4. WHEN a login attempt occurs, THE System SHALL record the attempt outcome in the login log.
5. WHEN a login attempt for an account succeeds, THE System SHALL reset the consecutive failed-attempt counter for that account.
6. THE System SHALL apply the failed-attempt counter and lockout per account.

#### Requirement 11.2: Session Control and Sensitive-Action Confirmation

**User Story:** As a security administrator, I want session invalidation, optional two-factor authentication, and re-confirmation for sensitive actions, so that account access is controlled and high-risk actions are deliberate.

#### Acceptance Criteria

1. WHEN a user logs out, THE System SHALL invalidate the user's active session so that the session can no longer authorize requests.
2. WHERE two-factor authentication is enabled for a user, THE System SHALL require a valid second factor before completing that user's login.
3. WHEN a user initiates a sensitive action designated as requiring re-confirmation, THE System SHALL require the user to re-confirm identity before the action executes.
4. WHEN an administrator invalidates a user's sessions, THE System SHALL terminate that user's active sessions.

### Capability Area 12: Approval Workflow Engine (P3)

#### Requirement 12.1: Configurable Approval Workflows

**User Story:** As an administrator, I want configurable approval policies with approvers, thresholds, and multiple levels, so that high-impact actions are reviewed before they take effect.

#### Acceptance Criteria

1. WHERE an action is governed by an enabled Approval Policy whose threshold is met, THE System SHALL place the action in a pending-approval state instead of executing it immediately.
2. WHEN an action is placed in a pending-approval state, THE System SHALL route the approval request to the approvers defined by the Approval Policy.
3. WHEN all required approval levels for an action are approved, THE System SHALL execute the action.
4. IF an approver rejects an action, THEN THE System SHALL cancel the action and record the rejection.
5. WHERE an Approval Policy defines multiple approval levels, THE System SHALL require approval at each level in the policy's defined order.
6. THE System SHALL NOT allow the user who initiated an action to approve that same action.
7. WHERE an Approval Policy defines an approval expiration and the action is not fully approved within that expiration, THE System SHALL mark the approval request as expired and cancel the action.

### Capability Area 13: AI Write-Back and Automation Execution (P3)

#### Requirement 13.1: Apply Recommendations to the Live Platform

**User Story:** As an operations user, I want approved keyword, bid, and listing recommendations applied to the live platform, so that optimizations take effect without manual re-entry on the platform.

#### Acceptance Criteria

1. WHEN a user applies an AI recommendation, THE System SHALL submit the corresponding change to the live platform through the Store's Platform Connection.
2. WHEN the System submits a change to the live platform, THE System SHALL record the change, the submitting user, and the platform response in the audit trail.
3. IF the live platform rejects a submitted change, THEN THE System SHALL record the failure reason and leave the affected internal record unchanged.
4. WHERE a recommendation application is governed by an Approval Policy, THE System SHALL require approval before submitting the change to the live platform.

#### Requirement 13.2: Automated Bid and Negative-Keyword Execution

**User Story:** As an operations user, I want automation rules to adjust bids and add negative keywords automatically, so that campaigns are optimized continuously within defined guardrails.

#### Acceptance Criteria

1. WHERE an automation rule for bid adjustment is enabled, THE System SHALL evaluate the rule against current performance data on each scheduled run.
2. WHEN a bid-adjustment rule's condition is met, THE System SHALL compute the adjusted bid within the rule's configured minimum and maximum bounds and submit the change to the live platform.
3. WHEN a negative-keyword rule's condition is met, THE System SHALL add the qualifying search term as a negative keyword and submit it to the live platform.
4. WHEN an automation rule executes a change, THE System SHALL record the change as automated in the audit trail.
5. IF an automated change would exceed the rule's configured bounds, THEN THE System SHALL clamp the change to the bound rather than apply an out-of-bounds value.
6. WHERE an automated change is governed by an Approval Policy whose threshold is met, THE System SHALL require approval before submitting the automated change to the live platform.
7. IF the live platform rejects an automated change, THEN THE System SHALL record the failure reason and leave the affected internal record unchanged.
