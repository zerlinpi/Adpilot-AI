# Requirements Document

## Introduction

This feature covers the repair and cleanup of the **AdPilot AI** cross-border e-commerce internal system. The work focuses on: (A) fixing the broken database initialization so the Java backend builds its schema on startup via Flyway, using a **single consolidated MySQL migration file** as the only SQL source; (B) converting the schema/seed SQL from PostgreSQL syntax to MySQL 8.0 and reconciling table/column names with the Java entities; (C) ensuring the Java backend can be built/packaged locally; (D) making all retained documentation consistent on MySQL as the single source of truth; (E) removing redundant SQL files, documentation, and dead code so that **README.md is the only retained documentation file**; (F) configuring the app for deployment (frontend on port 8080, backend on port 8090, server `http://YOUR_SERVER_IP/`) so it can be packaged and deployed directly; and (G) verifying frontend build, backend build, and Flyway init all work.

The system under repair consists of a React/TypeScript/Vite frontend, a Java 17 / Spring Boot 3.2.5 backend (the primary backend, 34 modules) using MyBatis Plus, JPA, and Flyway, a MySQL 8.0 database whose SQL lives under `bigdata/sql/`, and Redis caching. A deprecated Node/Express/Prisma backend (`legacy-backend/`) and an old Prisma schema directory (`database/`) are confirmed removable.

## Confirmed Audit Findings (verified against the codebase)

- The V1â€“V13 Flyway migration files under `bigdata/sql/migration/` are written in **PostgreSQL** syntax: confirmed counts include 194 `TIMESTAMPTZ`, 99 `uuid_generate_v4()`, 64 `JSONB`, 120 `NUMERIC(...)`, 2 `CREATE EXTENSION`, and 291 `UUID`-type usages. They cannot run on MySQL 8.0 as-is.
- `backend-java/src/main/resources/db/migration` does **not** exist; Flyway is configured (in `application.yml`, `application-dev.yml`, and hardcoded in `FlywayConfig.java`) to read `classpath:db/migration`, which is currently empty. Schema initialization therefore fails.
- `application.yml` sets `ddl-auto: validate` while `application-dev.yml` overrides to `update` â€?a conflict given Flyway owns the schema.
- The backend connector and dialect are MySQL (`com.mysql.cj.jdbc.Driver`, `MySQLDialect`), confirming **MySQL 8.0 is the source of truth**.
- Maven is available locally at `E:\apache-maven-3.9.9`; there is no Maven wrapper in `backend-java/`.

## PostgreSQL â†?MySQL 8.0 Conversion Rules (authoritative)

| PostgreSQL | MySQL 8.0 |
|---|---|
| `CREATE EXTENSION ... "uuid-ossp" / "pgcrypto"` | remove entirely |
| `id UUID PRIMARY KEY DEFAULT uuid_generate_v4()` | `id CHAR(36) PRIMARY KEY DEFAULT (UUID())` |
| `UUID` (FK columns) | `CHAR(36)` |
| `TIMESTAMPTZ DEFAULT NOW()` | `DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)` |
| auto-updating `updated_at` | `DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)` |
| `JSONB DEFAULT '{}'` / `'[]'` | `JSON` (prefer no default; or `JSON DEFAULT (JSON_OBJECT())` / `(JSON_ARRAY())` on MySQL 8.0.13+) |
| `NUMERIC(p,s)` | `DECIMAL(p,s)` |
| `BOOLEAN` | keep (`TINYINT(1)`) |
| `CHECK (...)` | keep only if valid on MySQL 8.0.16+, otherwise move to application-layer validation |
| `CREATE INDEX IF NOT EXISTS` | `CREATE INDEX idx_xxx ON t(col)` (Flyway runs once; no `IF NOT EXISTS` needed) |
| `INSERT ... ON CONFLICT DO NOTHING` | `INSERT IGNORE INTO ...` or `... ON DUPLICATE KEY UPDATE id = id` |

## Glossary

- **Backend**: The primary Java 17 / Spring Boot 3.2.5 application located in `backend-java/`.
- **Frontend**: The React + TypeScript + Vite application located in `frontend/`.
- **Flyway**: The database migration tool integrated into the Backend that applies versioned SQL migration scripts on startup.
- **Migration_Scripts**: The Flyway SQL migration(s) that define the database schema and seed data. The project SHALL consolidate these into a **single canonical MySQL 8.0 file** (e.g. `V1__init_schema.sql`, optionally a `V2__seed_data.sql` for seed/DML) located at `backend-java/src/main/resources/db/migration`. The legacy PostgreSQL files under `bigdata/sql/` (ddl/dml/migration/report) are the source material to be converted and then removed.
- **Consolidated_SQL_File**: The single MySQL 8.0 schema migration that replaces the previous 13 separate DDL/migration files. Seed data MAY live in one additional Flyway-versioned seed file.
- **Classpath_Migration_Location**: The directory `backend-java/src/main/resources/db/migration` that Flyway is configured to scan via `classpath:db/migration`.
- **Database**: The MySQL 8.0 database named `adpilot` that the Backend connects to.
- **DDL_Auto**: The Hibernate/JPA `spring.jpa.hibernate.ddl-auto` configuration property that controls automatic schema modification.
- **Maven_Wrapper**: The `mvnw`/`mvnw.cmd` scripts and supporting `.mvn/` files that allow building the Backend without a globally installed Maven. A local Maven install is available at `E:\apache-maven-3.9.9` for verification.
- **README**: The file `README.md` at the workspace root, designated as the single retained documentation file.
- **Redundant_Docs**: Documentation files designated for removal: the eight files in `docs/`, `bigdata/docs/database-dictionary.md`, `frontend/guidelines/Guidelines.md`, and `ATTRIBUTIONS.md`.
- **Dead_Code_Directories**: The deprecated directories `legacy-backend/` (Node/Express/Prisma) and `database/` (old Prisma schema), confirmed removable.
- **Source_Of_Truth**: MySQL 8.0 is the authoritative database technology for the project; all documentation must reflect MySQL, not PostgreSQL.

## Requirements

### Requirement 1: Wire Flyway Migrations into the Backend Classpath

**User Story:** As a backend developer, I want a single consolidated Flyway migration on the Backend classpath, so that the Database schema is created automatically when the Backend starts.

#### Acceptance Criteria

1. THE Backend SHALL contain the Consolidated_SQL_File within the Classpath_Migration_Location (`backend-java/src/main/resources/db/migration`).
2. WHERE the Backend resolves the Flyway location `classpath:db/migration`, THE Backend SHALL find the Consolidated_SQL_File (and an optional seed migration).
3. WHEN the Backend starts with an empty Database, THE Flyway SHALL apply the Consolidated_SQL_File successfully.
4. THE project SHALL retain exactly one canonical SQL source on the classpath and SHALL NOT keep multiple divergent copies of the schema.
5. THE Consolidated_SQL_File SHALL contain the full schema equivalent to the union of the previous thirteen DDL/migration files, with no tables lost.

### Requirement 2: Convert V1â€“V13 Migrations from PostgreSQL to MySQL 8.0

**User Story:** As a backend developer, I want the migration scripts converted to valid MySQL 8.0 syntax, so that Flyway applies them on a MySQL 8.0 database without errors.

#### Acceptance Criteria

1. THE Migration_Scripts SHALL use MySQL 8.0 dialect syntax exclusively and SHALL NOT contain any PostgreSQL-only construct.
2. THE Migration_Scripts SHALL NOT contain `CREATE EXTENSION`, `uuid_generate_v4()`, `uuid-ossp`, `pgcrypto`, `TIMESTAMPTZ`, `JSONB`, `NUMERIC(...)`, or `... ON CONFLICT ...`.
3. WHERE a PostgreSQL construct is present, THE Migration_Script SHALL be converted according to the PostgreSQL â†?MySQL 8.0 Conversion Rules table in this document.
4. WHEN Flyway applies the Migration_Scripts to a MySQL 8.0 Database, THE Flyway SHALL complete the migration without syntax errors.
5. THE Consolidated_SQL_File SHALL use Flyway versioned naming and SHALL NOT have duplicate version numbers among the migration files on the classpath.
6. WHERE `CREATE INDEX IF NOT EXISTS` appears, THE Migration_Script SHALL use plain `CREATE INDEX idx_xxx ON table(column)` because Flyway applies each script exactly once.

### Requirement 3: Schema / Entity Name Consistency

**User Story:** As a backend developer, I want table and column names in the migrations to match the Java entities and mappers, so that JPA validation and MyBatis queries succeed at runtime.

#### Acceptance Criteria

1. THE Migration_Scripts SHALL define table and column names that match the corresponding Java `@Entity` / `@TableName` / MyBatis mapper definitions.
2. WHERE a known mismatch exists, THE Migration_Scripts SHALL use the entity-aligned names: `suppliers` SHALL use `supplier_name` and `contact_email` (not `name`/`email`); warehouse inventory SHALL use `warehouse_location_id`, `quantity_on_hand`, `quantity_reserved`, `quantity_available`.
3. THE `data_scopes` table SHALL include `org_id` and `scope_config`, and SHALL use `scope_type` values `all_company` / `department` / `own` / `assigned_store` / `assigned_product`; the corresponding DML seed values SHALL map legacy `all` â†?`all_company` and `assigned` â†?`assigned_store`.
4. THE project SHALL NOT introduce duplicate tables for the same concept: it SHALL use `warehouse_locations` (not a new `warehouses`) and `customer_reviews` (not a new `reviews`).
5. WHERE the actual entities differ from the names listed above, THE migrations SHALL follow the actual entity definitions discovered in the codebase, and any deviation from this requirement SHALL be documented in the design.

### Requirement 4: Missing Tables Present in Migrations

**User Story:** As a backend developer, I want all tables referenced by the entities/mappers to exist in the migrations, so that the application does not fail on missing tables.

#### Acceptance Criteria

1. THE Migration_Scripts SHALL define the tables required by the backend that are currently missing, including: `login_logs`, `purchase_requests`, `cash_flow`, `receivables`, `payables`, and `customer_tickets`.
2. WHERE a table in this list already exists under a different name aligned to the entities, THE design SHALL document the reconciliation rather than creating a duplicate.
3. WHEN the Backend starts after migration, THE Backend SHALL NOT report missing-table errors for the listed tables.

### Requirement 5: Resolve JPA ddl-auto Configuration Conflict

**User Story:** As a backend developer, I want a single consistent schema-ownership policy, so that JPA validation does not conflict with Flyway-managed schema during startup.

#### Acceptance Criteria

1. THE Backend SHALL designate Flyway as the sole owner of Database schema changes.
2. THE DDL_Auto value in the base `application.yml` SHALL be set to either `validate` or `none`.
3. THE DDL_Auto value in `application-dev.yml` SHALL be consistent with the base `application.yml` and SHALL NOT be set to `update` or `create`.
4. WHEN the Backend starts after Flyway has applied all Migration_Scripts, THE Backend SHALL complete JPA initialization without schema-validation errors.

### Requirement 6: Backend Build Verification

**User Story:** As a developer without a globally installed Maven, I want a reliable way to build and verify the Backend, so that I can confirm the project compiles.

#### Acceptance Criteria

1. THE Backend SHALL include a Maven_Wrapper (`mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`).
2. WHEN a developer runs the Maven_Wrapper compile command from `backend-java/`, THE Backend SHALL compile all Java sources without compilation errors.
3. WHERE the Maven_Wrapper is used, THE Backend SHALL NOT require a pre-existing global Maven installation on the PATH.
4. WHEN the Backend is packaged via the Maven_Wrapper, THE Backend SHALL produce an executable artifact without build failures.
5. WHERE a global Maven is available at `E:\apache-maven-3.9.9`, THE verification steps MAY use it to run `clean compile` and `clean package`, and both SHALL succeed.

### Requirement 7: Flyway + MySQL Initialization Verification

**User Story:** As a backend developer, I want to verify Flyway initializes a clean MySQL database, so that I know the schema builds end-to-end.

#### Acceptance Criteria

1. WHEN an empty MySQL 8.0 Database `adpilot` (charset `utf8mb4`, collation `utf8mb4_unicode_ci`) is available and the Backend starts, THE Flyway SHALL create `flyway_schema_history` and record V1 through V13 as success.
2. WHEN Flyway has completed, THE Backend SHALL start without Flyway syntax errors and without JPA schema-validation errors.
3. IF a local MySQL instance is not reachable during this work, THEN THE verification SHALL fall back to static SQL validation of the converted scripts, AND THE README SHALL document the exact local verification steps (create DB, JDBC URL, start command).

### Requirement 8: Frontend-to-Backend Port and Proxy Consistency

**User Story:** As a developer, I want consistent and correct ports across frontend, backend, and docs, so that the app works in development and after deployment.

#### Acceptance Criteria

1. THE Backend SHALL run on port `8090`, and THE deployed Frontend SHALL be served on port `8080`.
2. THE Frontend Vite dev proxy for `/api` SHALL target the Backend on port `8090`.
3. WHERE the README, `vite.config.ts`, and `application.yml` reference ports, THEY SHALL agree: Backend `8090`, Frontend `8080`.
4. IF an inconsistency exists between the documented proxy port and the actual backend `server.port`, THEN it SHALL be corrected so the Frontend reaches the Backend.

### Requirement 9: Documentation Consistency on MySQL

**User Story:** As a developer reading project documentation, I want all retained documentation to describe MySQL consistently, so that I am not misled by PostgreSQL references.

#### Acceptance Criteria

1. THE README SHALL describe the Database as MySQL 8.0 as the Source_Of_Truth.
2. THE README SHALL NOT contain PostgreSQL references, `psql` commands, or PostgreSQL-specific connection instructions.
3. WHERE database setup or connection instructions appear in the README, THE README SHALL use MySQL 8.0 commands and the MySQL JDBC connection format.
4. THE README SHALL describe schema initialization as performed by Flyway applying the Migration_Scripts on Backend startup.

### Requirement 10: Merge Useful Content Before Deletion

**User Story:** As a project maintainer, I want genuinely useful content from removed docs preserved in the README, so that no critical information is lost during cleanup.

#### Acceptance Criteria

1. BEFORE deleting any Redundant_Docs, THE maintainer SHALL identify content in the Redundant_Docs that is genuinely useful and not already present in the README.
2. WHERE useful content is identified in a Redundant_Doc, THE README SHALL be updated to incorporate that content before the Redundant_Doc is deleted.
3. THE README SHALL remain internally consistent and free of duplicated sections after content is merged.

### Requirement 11: Documentation and Redundant SQL Cleanup Keeping Only README

**User Story:** As a project maintainer, I want only README.md retained as documentation and only the consolidated SQL kept, so that the repository has a single authoritative doc and a single SQL source.

#### Acceptance Criteria

1. THE project SHALL delete the eight Markdown files in `docs/` (DATABASE_SCHEMA, DEPLOYMENT_GUIDE, INTERNAL_TRIAL_PLAN, MASTER_PROJECT_STATUS, PRODUCT_SPEC, REALITY_AUDIT_REPORT, SECURITY_CHECKLIST, UAT_CHECKLIST).
2. THE project SHALL delete `bigdata/docs/database-dictionary.md`.
3. THE project SHALL delete `frontend/guidelines/Guidelines.md`.
4. THE project SHALL delete `ATTRIBUTIONS.md`.
5. THE README SHALL be retained as the single documentation file at the workspace root.
6. WHERE a directory becomes empty after its documentation files are deleted, THE project SHALL remove the now-empty directory.
7. AFTER the schema is consolidated into the Consolidated_SQL_File on the classpath, THE project SHALL delete the now-redundant SQL files under `bigdata/sql/` (the separate `ddl/`, `dml/`, `migration/`, `report/` files and `init_*.sql`), keeping only the single canonical SQL source.
8. WHERE the project chooses to keep a human-readable copy of the consolidated SQL outside the classpath, THE project SHALL keep at most one such file and SHALL ensure it does not diverge from the classpath copy (or SHALL reference the classpath copy as canonical).

### Requirement 12: Dead Code Removal

**User Story:** As a project maintainer, I want deprecated backends and schemas removed, so that the repository contains only the active codebase.

#### Acceptance Criteria

1. THE project SHALL delete the `legacy-backend/` directory.
2. THE project SHALL delete the `database/` directory containing the old Prisma schema.
3. WHILE performing deletions, THE project SHALL exclude any `node_modules` directory from recursive deletion operations.
4. IF a recursive deletion encounters a `node_modules` path, THEN THE deletion process SHALL skip that path and continue without failing.

### Requirement 13: Frontend Build Integrity

**User Story:** As a frontend developer, I want the Frontend to still build after cleanup, so that removing documentation and dead code does not break the application.

#### Acceptance Criteria

1. WHEN the Frontend is built with `pnpm` after cleanup, THE Frontend SHALL complete the build without errors.
2. THE cleanup operations SHALL NOT delete or modify any source file required by the Frontend build.
3. WHERE `frontend/guidelines/Guidelines.md` is deleted, THE Frontend build SHALL remain unaffected because the file is documentation only.

### Requirement 14: Packaging and Deployment Configuration

**User Story:** As a maintainer, I want the app pre-configured for the target server, so that I can package and deploy it directly without further edits.

#### Acceptance Criteria

1. THE Backend SHALL provide a production profile (`application-prod.yml`) configured for MySQL 8.0, Redis, and `server.port: 8090`.
2. THE production CORS / allowed-origins configuration SHALL include the deployment origin `http://YOUR_SERVER_IP:8080` (and `http://YOUR_SERVER_IP`) so the Frontend can call the Backend.
3. THE Frontend production build SHALL be served on port `8080` and SHALL reach the Backend API at the deployment host on port `8090` (via reverse proxy or configured API base URL).
4. THE README SHALL document a single, ordered deployment procedure: build backend jar (`mvnw clean package`), build frontend (`pnpm build`), create the MySQL `adpilot` database, run the backend (Flyway initializes the schema), and serve the frontend on `8080`.
5. THE README SHALL state the target server as `http://YOUR_SERVER_IP/` with Frontend on `8080` and Backend on `8090`.
6. WHERE secrets (JWT secret, DB password) are used in production, THE README SHALL instruct changing them from development defaults before deployment.
