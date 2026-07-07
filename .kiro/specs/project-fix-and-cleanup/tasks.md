# Implementation Plan: Project Fix and Cleanup

## Overview

This plan repairs the AdPilot AI system by consolidating the database schema into a single MySQL 8.0 Flyway migration on the backend classpath, reconciling entities with the schema, fixing JPA/config/port/CORS consistency, adding a Maven wrapper, consolidating documentation into README, deleting redundant SQL/docs/dead code, and verifying backend build, frontend build, and Flyway initialization.

Per the design's Testing Strategy, property-based testing does not apply to this SQL-migration, configuration, documentation, and cleanup work. Verification relies on static SQL validation, schema/entity consistency checks, build verification, and a Flyway + MySQL initialization smoke/integration test.

## Tasks

- [x] 1. Discover and inventory entity/schema source material
  - [x] 1.1 Inventory entity table/column names and PostgreSQL columnDefinition literals
    - Scan `backend-java/src/main/java/com/adpilot/**/entity/**/*.java` for all `@TableName`/`@Table`/`@TableField`/`@Column` names
    - Record every `columnDefinition = "uuid"` and `columnDefinition = "jsonb"` occurrence with its file and field, producing the complete edit list referenced by the design
    - Cross-reference the reconciled tables: `suppliers` (`supplier_name`, `contact_email`), `warehouse_inventory` (`warehouse_location_id`, `quantity_on_hand`, `quantity_reserved`, `quantity_available`), `warehouse_locations`, `customer_reviews`, `customer_tickets`, `data_scopes` (`role_id`, `scope_type`, `store_ids`, `product_ids`)
    - _Requirements: 3.1, 3.2, 3.4, 3.5_

  - [x] 1.2 Inventory legacy SQL source material
    - Read `bigdata/sql/init_mysql.sql` as the primary (~95% converted) source material
    - Enumerate the PostgreSQL `bigdata/sql/ddl/`, `dml/`, `migration/` (V1鈥揤13) tables to identify any tables absent from `init_mysql.sql`, including `login_logs` and `purchase_requests`
    - _Requirements: 1.5, 2.1, 4.1_

- [x] 2. Create the classpath Flyway schema migration
  - [x] 2.1 Create the classpath migration directory and V1__init_schema.sql
    - Create directory `backend-java/src/main/resources/db/migration`
    - Author `V1__init_schema.sql` (MySQL 8.0 DDL) by adopting/completing `bigdata/sql/init_mysql.sql`, covering the union of the 13 DDL/migration files with no tables lost
    - Standardize id/FK columns to `CHAR(36)` (with `DEFAULT (UUID())` on primary keys), `JSON` for jsonb columns, `DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)` (and `ON UPDATE CURRENT_TIMESTAMP(3)` for `updated_at`), and `DECIMAL(p,s)` for numerics
    - Apply the PostgreSQL鈫扢ySQL 8.0 conversion rules; do not emit `CREATE EXTENSION`, `uuid_generate_v4()`, `TIMESTAMPTZ`, `JSONB`, `NUMERIC(...)`, `ON CONFLICT`, or `CREATE INDEX IF NOT EXISTS`
    - _Requirements: 1.1, 1.2, 1.4, 1.5, 2.1, 2.2, 2.3, 2.6_

  - [x] 2.2 Reconcile entity-aligned table/column names in V1__init_schema.sql
    - Use entity-aligned names from task 1.1: `suppliers.supplier_name`/`contact_email`; `warehouse_inventory` quantity columns; keep `warehouse_locations` and `customer_reviews` (no `warehouses`/`reviews` duplicates)
    - Define `data_scopes` per the `DataScope` entity (`role_id`, `scope_type`, `store_ids` JSON, `product_ids` JSON, timestamps); honor the documented Req 3.3 deviation (no `org_id`/`scope_config`)
    - _Requirements: 3.1, 3.2, 3.4, 3.5_

  - [x] 2.3 Add the previously-missing tables to V1__init_schema.sql
    - Define `login_logs`, `purchase_requests`, `cash_flow`, `receivables`, `payables`, `customer_tickets` in MySQL 8.0 syntax
    - Keep `purchase_requests` distinct from `purchase_orders` per the documented reconciliation
    - _Requirements: 4.1, 4.2_

  - [x] 2.4 Create V2__seed_data.sql
    - Author `V2__seed_data.sql` (MySQL 8.0 DML) as the union of the seed/DML files, idempotent via `INSERT IGNORE`
    - Map legacy `data_scopes.scope_type` values `all` 鈫?`all_company` and `assigned` 鈫?`assigned_store`
    - _Requirements: 1.1, 1.2, 2.2, 2.3, 3.3_

  - [x] 2.5 Static SQL validation of the consolidated migrations
    - Scan `V1__init_schema.sql` and `V2__seed_data.sql` asserting zero occurrences of `TIMESTAMPTZ`, `JSONB`, `uuid_generate_v4`, `uuid-ossp`, `pgcrypto`, `CREATE EXTENSION`, `NUMERIC(`, `ON CONFLICT`, `CREATE INDEX IF NOT EXISTS`
    - Confirm Flyway versioned naming with no duplicate version numbers on the classpath
    - _Requirements: 2.2, 2.4, 2.5_

  - [x] 2.6 Schema/entity consistency check
    - Cross-check every reconciled `@Table`/`@Column` name against the consolidated DDL (suppliers, warehouse_inventory, warehouse_locations, customer_reviews, customer_tickets, data_scopes, and the six previously-missing tables)
    - _Requirements: 3.1, 3.2, 4.3_

- [x] 3. Normalize entity columnDefinition literals to MySQL types
  - [x] 3.1 Edit entity columnDefinition values from PostgreSQL to MySQL literals
    - Using the list from task 1.1, change `columnDefinition = "uuid"` 鈫?`"char(36)"` and `columnDefinition = "jsonb"` 鈫?`"json"` across all affected entities (e.g. `User`, `DataScope` `storeIds`/`productIds`, `WarehouseInventoryEntity`, `ProductUploadJobEntity` `payload`/`response`, and others discovered)
    - Ensure edited definitions match the column types created by `V1__init_schema.sql` so Hibernate `validate` passes
    - _Requirements: 3.1, 5.4_

- [x] 4. Resolve JPA ddl-auto configuration conflict
  - [x] 4.1 Align ddl-auto across all profiles to Flyway-owned schema
    - Keep `spring.jpa.hibernate.ddl-auto: validate` in `application.yml`
    - Change `application-dev.yml` from `update` to `validate` (or `none`); confirm `application-prod.yml` is `validate`
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 4.2 Unit/example check for ddl-auto values
    - Add a focused test or repeatable check asserting the three `ddl-auto` values are `validate`/`none` (never `update`/`create`)
    - _Requirements: 5.2, 5.3_

- [x] 5. Add the Maven wrapper
  - [x] 5.1 Generate the Maven wrapper in backend-java
    - Add `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` (pinned to a Maven 3.9.x distribution), generated via the local Maven at `E:\apache-maven-3.9.9`
    - _Requirements: 6.1, 6.3_

- [x] 6. Make CORS property-driven and ports/proxy consistent
  - [x] 6.1 Refactor WebConfig CORS to read allowed origins from configuration
    - Change `WebConfig.java` to read `adpilot.cors.allowed-origins` instead of hardcoding `http://localhost:5173`
    - _Requirements: 14.2_

  - [x] 6.2 Configure prod CORS origins and confirm ports
    - Set `application-prod.yml` allowed origins to include `http://YOUR_SERVER_IP:8080` and `http://YOUR_SERVER_IP`; keep `server.port: 8090`
    - Confirm `vite.config.ts` `/api` proxy targets the backend on port `8090`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 14.1, 14.2, 14.3_

  - [x] 6.3 Unit/example check for CORS configuration
    - Add a focused test or check that `WebConfig` resolves CORS origins from `adpilot.cors.allowed-origins` and includes the deployment origins under the prod profile
    - _Requirements: 14.2_

- [x] 7. Checkpoint - verify backend compiles
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Consolidate documentation into README
  - [x] 8.1 Merge useful content from redundant docs into README before deletion
    - Identify genuinely useful, non-duplicated content in the eight `docs/` files, `bigdata/docs/database-dictionary.md`, `frontend/guidelines/Guidelines.md`, and `ATTRIBUTIONS.md`; merge into `README.md` (deployment steps, security/secret-rotation notes, schema dictionary highlights, attributions)
    - Keep README internally consistent and free of duplicated sections
    - _Requirements: 10.1, 10.2, 10.3_

  - [x] 8.2 Rewrite README to MySQL 8.0 source of truth and deployment procedure
    - Remove any PostgreSQL references / `psql` commands; describe Database as MySQL 8.0 and schema init via Flyway on backend startup
    - Correct all port references (Backend `8090`, Frontend `8080`); fix the wrong "proxy targets localhost:8080" statement
    - Document one ordered deployment procedure: `mvnw clean package`, `pnpm build`, create MySQL `adpilot` DB, run backend (Flyway initializes schema), serve frontend on `8080`; state target server `http://YOUR_SERVER_IP/`; instruct rotating JWT secret and DB password from dev defaults
    - Document the local Flyway verification steps (create DB, JDBC URL, start command) as the Req 7.3 fallback
    - _Requirements: 8.3, 9.1, 9.2, 9.3, 9.4, 14.4, 14.5, 14.6, 7.3_

- [x] 9. Delete redundant SQL, docs, and dead code
  - [x] 9.1 Delete redundant SQL under bigdata/sql after consolidation
    - Delete `bigdata/sql/ddl/`, `dml/`, `migration/`, `report/`, `init_all.sql`, and `init_mysql.sql`, keeping only the canonical classpath copy (no divergent copy retained)
    - _Requirements: 11.7, 11.8, 1.4_

  - [x] 9.2 Delete redundant documentation files and now-empty directories
    - Delete the eight `docs/` Markdown files, `bigdata/docs/database-dictionary.md`, `frontend/guidelines/Guidelines.md`, and `ATTRIBUTIONS.md`; remove directories left empty; retain `README.md` only
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

  - [x] 9.3 Delete dead-code directories excluding node_modules
    - Delete `legacy-backend/` and `database/` using a deletion approach that skips `node_modules` paths and continues on error
    - _Requirements: 12.1, 12.2, 12.3, 12.4_

- [x] 10. Verification
  - [x] 10.1 Backend build verification
    - Run `./mvnw clean compile` and `./mvnw clean package` from `backend-java/` (cross-check with global Maven at `E:\apache-maven-3.9.9`); both succeed without a global Maven on PATH
    - _Requirements: 6.2, 6.4, 6.5_

  - [x] 10.2 Frontend build verification
    - Run `pnpm install && pnpm build` in `frontend/`; build completes without errors after cleanup
    - _Requirements: 13.1, 13.2, 13.3_

  - [x] 10.3 Flyway + MySQL initialization smoke/integration test
    - Against an empty MySQL 8.0 `adpilot` (charset `utf8mb4`, collation `utf8mb4_unicode_ci`), start the backend and confirm Flyway creates `flyway_schema_history`, records the consolidated migrations as success, and the backend starts with no Flyway syntax errors and no JPA `validate` errors and no missing-table errors
    - If MySQL is unreachable, fall back to the static SQL validation (task 2.5) and the documented README verification steps
    - _Requirements: 1.3, 2.4, 4.3, 5.4, 7.1, 7.2, 7.3_

- [x] 11. Final checkpoint - ensure all builds and initialization pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional verification sub-tasks and can be skipped for a faster path, though they are the primary safety net for this work.
- Property-based testing is intentionally not used; per the design, this is SQL-migration, configuration, documentation, and cleanup work without a meaningful universal input space. Verification uses static SQL validation, schema/entity consistency checks, build verification, and a Flyway-init smoke/integration test.
- Each task references specific requirements clauses for traceability.
- Documentation is merged into README before any redundant doc is deleted (merge-before-delete) to avoid content loss.
- Deletions skip `node_modules` to avoid long/locked-path failures on Windows.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "5.1", "6.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "4.1", "6.2"] },
    { "id": 2, "tasks": ["2.2", "4.2", "6.3"] },
    { "id": 3, "tasks": ["2.3"] },
    { "id": 4, "tasks": ["2.4"] },
    { "id": 5, "tasks": ["2.5", "2.6"] },
    { "id": 6, "tasks": ["8.1"] },
    { "id": 7, "tasks": ["8.2"] },
    { "id": 8, "tasks": ["9.1", "9.2", "9.3"] },
    { "id": 9, "tasks": ["10.1", "10.2", "10.3"] }
  ]
}
```
