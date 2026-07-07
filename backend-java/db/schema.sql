-- =====================================================================
-- AdPilot — Consolidated Database Schema (MySQL 8.0)
-- =====================================================================
-- THIS IS THE SINGLE AUTHORITATIVE SCHEMA FILE.
--
-- The application no longer runs migrations on startup (Flyway is disabled
-- and Hibernate ddl-auto is `none`). The database is decoupled from the app
-- and must be provisioned manually by importing this file into a fresh,
-- empty MySQL 8.0 database:
--
--     mysql -u <user> -p adpilot < db/schema.sql
--
-- This file is the merge, IN ORDER, of the former Flyway migrations
-- V1 through V6:
--   * V1 : full base schema (DDL)
--   * V2 : AI advertising module — ALTER campaigns (hosting columns) + ad_portfolios
--   * V3 : ai_workitems
--   * V4 : keyword_rank_creative
--   * V5 : feishu_notification_rules
--   * V6 : automation_rule_templates
--
-- V2's ALTER TABLE statements are kept as-is, appended in order after the V1
-- `campaigns` CREATE TABLE, which applies cleanly on a fresh import.
--
-- Going forward, ALL schema changes are made HERE. This is the single source
-- of truth; there are no per-version migration files anymore.
-- =====================================================================

-- Force the import session to utf8mb4 so this UTF-8 file's non-ASCII seed data
-- (role names, store-group names, permission labels, sample data) is stored
-- correctly even if the importing client's default charset is not utf8mb4.
-- Pair this with creating the database as utf8mb4 (see README deploy guide) so
-- every table inherits utf8mb4 without per-table charset clauses.
SET NAMES utf8mb4;


-- =====================================================================
-- ===  V1__init_schema.sql  ===========================================
-- =====================================================================
-- =====================================================================
-- V1__init_schema.sql  (SINGLE CONSOLIDATED MIGRATION)
-- The one and only Flyway migration for AdPilot (MySQL 8.0).
-- V1 through V12 have been merged into this single, self-contained file
-- that runs successfully on a fresh/empty MySQL 8.0 database:
--   * V1  : full base schema (DDL)
--   * V2  : user_stores (data scoping by store)
--   * V3  : sync foundation (sync_watermarks, sync_record_errors) + ai_settings
--   * V4  : store grouping (stores.store_group) + default store (users.default_store_id)
--   * V5  : Amazon seller account (platform_connections.seller_account_id)
--   * V6  : exchange_rates
--   * V7  : settlement reconciliation + multi-currency provenance (settlements.*)
--   * V8  : unified alert center (alerts)
--   * V9  : account security columns (users.*)
--   * V10 : approval workflow (approval_requests.* + approval_decisions)
--   * V11 : automation_rules
--   * V12 : marketplace VAT (marketplaces.vat_applicable / vat_rate)
--   * V13 : Amazon Ads OAuth wizard (platform_connections.region / profile_id /
--           marketplace_id / refresh_token_encrypted)
--   * Seed / reference data (idempotent INSERT IGNORE + VAT UPDATEs)
-- Every column added by a later migration has been folded directly into its
-- CREATE TABLE definition; no ALTER TABLE statements remain.
-- =====================================================================
-- MySQL 8.0 conventions:
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * jsonb -> JSON
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--     (updated_at also ON UPDATE CURRENT_TIMESTAMP(3))
--   * other timestamps -> DATETIME(3); numerics -> DECIMAL(p,s)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Idempotent index helper. MySQL 8.0 does not support CREATE INDEX IF
-- NOT EXISTS, so every standalone index below goes through this helper.
-- Re-importing schema.sql into an existing database can then add missing
-- indexes without failing on indexes that already exist.
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS adpilot_create_index_if_missing;
DROP PROCEDURE IF EXISTS adpilot_add_column_if_missing;
DROP PROCEDURE IF EXISTS adpilot_replace_check_constraint;
DELIMITER $$
CREATE PROCEDURE adpilot_add_column_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_column_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND column_name = p_column_name
    ) THEN
        SET @adpilot_column_sql = CONCAT(
            'ALTER TABLE `', REPLACE(p_table_name, '`', '``'),
            '` ADD COLUMN `', REPLACE(p_column_name, '`', '``'), '` ',
            p_column_sql
        );
        PREPARE adpilot_column_stmt FROM @adpilot_column_sql;
        EXECUTE adpilot_column_stmt;
        DEALLOCATE PREPARE adpilot_column_stmt;
    END IF;
END$$

CREATE PROCEDURE adpilot_create_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_columns_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @adpilot_index_sql = CONCAT(
            'CREATE INDEX `', REPLACE(p_index_name, '`', '``'),
            '` ON `', REPLACE(p_table_name, '`', '``'), '` ',
            p_columns_sql
        );
        PREPARE adpilot_index_stmt FROM @adpilot_index_sql;
        EXECUTE adpilot_index_stmt;
        DEALLOCATE PREPARE adpilot_index_stmt;
    END IF;
END$$

-- Idempotent UNIQUE-index creation helper. Mirrors
-- adpilot_create_index_if_missing but emits CREATE UNIQUE INDEX, so an
-- enforced grain (e.g. the search-term aggregation key) can be added to a
-- pre-existing table without a bare ALTER TABLE. No-op once the index exists.
CREATE PROCEDURE adpilot_create_unique_index_if_missing(
    IN p_table_name VARCHAR(128),
    IN p_index_name VARCHAR(128),
    IN p_columns_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @adpilot_uidx_sql = CONCAT(
            'CREATE UNIQUE INDEX `', REPLACE(p_index_name, '`', '``'),
            '` ON `', REPLACE(p_table_name, '`', '``'), '` ',
            p_columns_sql
        );
        PREPARE adpilot_uidx_stmt FROM @adpilot_uidx_sql;
        EXECUTE adpilot_uidx_stmt;
        DEALLOCATE PREPARE adpilot_uidx_stmt;
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Idempotent CHECK-constraint replacement helper. MySQL stores inline
-- column CHECKs under an auto-generated constraint name, so widening an
-- allowed-value list (e.g. adding a new platform_family) cannot be done by
-- name. This helper is a no-op once the target named constraint exists;
-- otherwise it drops every existing CHECK on the table and re-adds the
-- desired clause under p_constraint_name. Re-importing schema.sql is safe.
-- ---------------------------------------------------------------------
CREATE PROCEDURE adpilot_replace_check_constraint(
    IN p_table_name VARCHAR(128),
    IN p_constraint_name VARCHAR(128),
    IN p_check_clause TEXT
)
BEGIN
    DECLARE v_old_name VARCHAR(128);
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = p_table_name
          AND constraint_name = p_constraint_name
          AND constraint_type = 'CHECK'
    ) THEN
        check_loop: LOOP
            SET v_old_name = (
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                WHERE tc.constraint_schema = DATABASE()
                  AND tc.table_name = p_table_name
                  AND tc.constraint_type = 'CHECK'
                LIMIT 1
            );
            IF v_old_name IS NULL THEN
                LEAVE check_loop;
            END IF;
            SET @adpilot_drop_sql = CONCAT(
                'ALTER TABLE `', REPLACE(p_table_name, '`', '``'),
                '` DROP CHECK `', REPLACE(v_old_name, '`', '``'), '`'
            );
            PREPARE adpilot_drop_stmt FROM @adpilot_drop_sql;
            EXECUTE adpilot_drop_stmt;
            DEALLOCATE PREPARE adpilot_drop_stmt;
        END LOOP;
        SET @adpilot_check_sql = CONCAT(
            'ALTER TABLE `', REPLACE(p_table_name, '`', '``'),
            '` ADD CONSTRAINT `', REPLACE(p_constraint_name, '`', '``'),
            '` CHECK ', p_check_clause
        );
        PREPARE adpilot_check_stmt FROM @adpilot_check_sql;
        EXECUTE adpilot_check_stmt;
        DEALLOCATE PREPARE adpilot_check_stmt;
    END IF;
END$$
DELIMITER ;
-- Organizations
CREATE TABLE IF NOT EXISTS organizations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    name VARCHAR(255) NOT NULL,
    plan VARCHAR(50) DEFAULT 'growth',
    logo_url TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Departments
CREATE TABLE IF NOT EXISTS departments (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    parent_id CHAR(36) REFERENCES departments(id),
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100),
    manager_user_id CHAR(36),
    sort_order INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Users
CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(500),
    avatar_url TEXT,
    phone VARCHAR(50),
    status VARCHAR(20) DEFAULT 'active',
    last_login_at DATETIME(3),
    -- V4: per-user default store selected on next login (Req 5.2.3)
    default_store_id CHAR(36) NULL,
    -- V9: account security (login-failure lockout + 2FA)
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until DATETIME(3) NULL,
    twofa_enabled TINYINT(1) NOT NULL DEFAULT 0,
    twofa_secret VARCHAR(255) NULL,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(org_id, email)
);

-- Roles
CREATE TABLE IF NOT EXISTS roles (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100) NOT NULL,
    description TEXT,
    is_system TINYINT(1) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(org_id, code)
);

-- Permissions
CREATE TABLE IF NOT EXISTS permissions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    code VARCHAR(200) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    module VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    description TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- User-Role mapping
CREATE TABLE IF NOT EXISTS user_roles (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id CHAR(36) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(user_id, role_id)
);

-- Role-Permission mapping
CREATE TABLE IF NOT EXISTS role_permissions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    role_id CHAR(36) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id CHAR(36) NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(role_id, permission_id)
);

-- User-Department mapping
CREATE TABLE IF NOT EXISTS user_departments (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    department_id CHAR(36) NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    is_primary TINYINT(1) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(user_id, department_id)
);

-- Data Scope (role-based data access)
-- Aligned to DataScope entity (Req 3.5 documented deviation): no org_id /
-- scope_config; uses store_ids/product_ids JSON. No restrictive scope_type
-- CHECK so entity values (all_company/department/own/assigned_store/
-- assigned_product) are accepted.
-- platform-workspace-rbac (Req 13, 18.3): scope_type additionally accepts
-- 'assigned_store_group'; the assigned store-group ids are carried in the new
-- store_group_ids JSON column, mirroring store_ids/product_ids. Existing
-- dimensions are preserved unchanged.
CREATE TABLE IF NOT EXISTS data_scopes (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    role_id CHAR(36) NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    scope_type VARCHAR(50) NOT NULL,
    store_ids JSON,
    product_ids JSON,
    store_group_ids JSON,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Approval Policies
CREATE TABLE IF NOT EXISTS approval_policies (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    module VARCHAR(100) NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    risk_level VARCHAR(20) DEFAULT 'low',
    approval_type VARCHAR(50) DEFAULT 'none' CHECK (approval_type IN ('none', 'direct_manager', 'role', 'department', 'multi_level', 'feishu')),
    approver_role_ids JSON DEFAULT (JSON_ARRAY()),
    approver_user_ids JSON DEFAULT (JSON_ARRAY()),
    enabled TINYINT(1) DEFAULT 1,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) REFERENCES users(id),
    org_id CHAR(36) REFERENCES organizations(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id CHAR(36),
    old_data JSON,
    new_data JSON,
    ip_address VARCHAR(50),
    user_agent TEXT,
    source VARCHAR(50) DEFAULT 'app',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('users', 'idx_users_org', '(org_id)');
CALL adpilot_create_index_if_missing('users', 'idx_users_email', '(email)');
CALL adpilot_create_index_if_missing('users', 'idx_users_status', '(status)');

-- project-fix-and-cleanup (login 500 fix): the V4/V9 columns below were folded
-- into the CREATE TABLE above but had NO add-column reconciliation, so
-- re-importing schema.sql onto a pre-existing (stale) `users` table left them
-- missing and every login SELECT failed with "unknown column" -> HTTP 500.
-- These idempotent calls add each column only when absent, so a stale database
-- is reconciled on re-import while a fresh import is unaffected (all no-ops).
CALL adpilot_add_column_if_missing('users', 'last_login_at', 'DATETIME(3) NULL');
CALL adpilot_add_column_if_missing('users', 'default_store_id', 'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('users', 'failed_login_count', 'INT NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('users', 'locked_until', 'DATETIME(3) NULL');
CALL adpilot_add_column_if_missing('users', 'twofa_enabled', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('users', 'twofa_secret', 'VARCHAR(255) NULL');
CALL adpilot_add_column_if_missing('users', 'created_by', 'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('users', 'updated_by', 'CHAR(36) NULL');
CALL adpilot_create_index_if_missing('departments', 'idx_departments_org', '(org_id)');
CALL adpilot_create_index_if_missing('departments', 'idx_departments_parent', '(parent_id)');
CALL adpilot_create_index_if_missing('roles', 'idx_roles_org', '(org_id)');
CALL adpilot_create_index_if_missing('user_roles', 'idx_user_roles_user', '(user_id)');
CALL adpilot_create_index_if_missing('user_roles', 'idx_user_roles_role', '(role_id)');
CALL adpilot_create_index_if_missing('role_permissions', 'idx_role_permissions_role', '(role_id)');
CALL adpilot_create_index_if_missing('role_permissions', 'idx_role_permissions_perm', '(permission_id)');
CALL adpilot_create_index_if_missing('user_departments', 'idx_user_departments_user', '(user_id)');
CALL adpilot_create_index_if_missing('user_departments', 'idx_user_departments_dept', '(department_id)');
CALL adpilot_create_index_if_missing('data_scopes', 'idx_data_scopes_role', '(role_id)');
-- platform-workspace-rbac (Req 13, 18.3): ensure the store-group scope column
-- exists on a pre-existing `data_scopes` table, for clean re-import.
CALL adpilot_add_column_if_missing('data_scopes', 'store_group_ids', 'JSON');
CALL adpilot_create_index_if_missing('approval_policies', 'idx_approval_policies_org', '(org_id)');
CALL adpilot_create_index_if_missing('audit_logs', 'idx_audit_logs_user', '(user_id)');
CALL adpilot_create_index_if_missing('audit_logs', 'idx_audit_logs_entity', '(entity_type, entity_id)');
CALL adpilot_create_index_if_missing('audit_logs', 'idx_audit_logs_action', '(action)');
CALL adpilot_create_index_if_missing('audit_logs', 'idx_audit_logs_created', '(created_at)');
-- Marketplaces
CREATE TABLE IF NOT EXISTS marketplaces (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    code VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    flag VARCHAR(10),
    -- advertising-workspace-rework (Req 51.12): IANA Marketplace_Timezone used
    -- for day-boundary / scheduling computation; no server-timezone fallback.
    timezone VARCHAR(64),
    -- V12: VAT applicability flag and rate per marketplace (Req 9.2.3)
    vat_applicable BOOLEAN NOT NULL DEFAULT FALSE,
    vat_rate DECIMAL(6,4),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- project-fix-and-cleanup: reconcile post-V1 marketplaces columns (timezone +
-- V12 VAT) onto a pre-existing table so a stale DB is upgraded on re-import.
CALL adpilot_add_column_if_missing('marketplaces', 'timezone', 'VARCHAR(64) NULL');
CALL adpilot_add_column_if_missing('marketplaces', 'vat_applicable', 'BOOLEAN NOT NULL DEFAULT FALSE');
CALL adpilot_add_column_if_missing('marketplaces', 'vat_rate', 'DECIMAL(6,4) NULL');

-- Store Groups (platform-workspace-rbac Req 10, 11, 18)
-- First-class grouping of stores within a platform family. Per-family
-- `is_default` group provides the fallback for unassigned stores (Req 10.7,
-- 18.2). UNIQUE(org_id, platform_family, name) enforces name uniqueness within
-- a platform family (Req 10.4).
CREATE TABLE IF NOT EXISTS store_groups (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(100) NOT NULL,
    platform_family VARCHAR(20) NOT NULL
        CHECK (platform_family IN ('amazon', 'independent_site')),
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_store_group_name (org_id, platform_family, name)
);

CALL adpilot_create_index_if_missing('store_groups', 'idx_store_groups_org', '(org_id)');
CALL adpilot_create_index_if_missing('store_groups', 'idx_store_groups_family', '(platform_family)');

-- Stores
CREATE TABLE IF NOT EXISTS stores (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    marketplace_id CHAR(36) NOT NULL REFERENCES marketplaces(id),
    seller_id VARCHAR(255),
    status VARCHAR(20) DEFAULT 'connected' CHECK (status IN ('connected', 'disconnected', 'error')),
    settings JSON DEFAULT (JSON_OBJECT()),
    -- V4: optional group label for organizing stores in the switcher (Req 5.2.4)
    store_group VARCHAR(100) NULL,
    -- platform-workspace-rbac (Req 10.2, 18): first-class store-group association.
    -- The legacy free-text `store_group` label above is retained and reconciled
    -- by migration (Req 18.2). `platform_family` is derived from the
    -- marketplace/platform; 'amazon' | 'independent_site'.
    store_group_id CHAR(36) NULL REFERENCES store_groups(id),
    platform_family VARCHAR(20) NULL,
    -- advertising-workspace-rework (Req 49.2): Store_Default_Personality used when
    -- a Campaign's personality cannot be resolved from a Campaign/Goal override.
    default_personality VARCHAR(20),
    -- advertising-workspace-rework (Req 12.3): per-store policy when the Store is
    -- not write-capable; exactly one of 'allow_local_draft' or 'forbid'.
    not_write_capable_policy VARCHAR(30) NOT NULL DEFAULT 'allow_local_draft'
        CHECK (not_write_capable_policy IN ('allow_local_draft', 'forbid')),
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- User <-> Store assignment (V2: data scoping by store)
CREATE TABLE IF NOT EXISTS user_stores (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL,
    store_id CHAR(36) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_store (user_id, store_id)
);

CALL adpilot_create_index_if_missing('user_stores', 'idx_user_stores_user', '(user_id)');
CALL adpilot_create_index_if_missing('user_stores', 'idx_user_stores_store', '(store_id)');

-- Products
CREATE TABLE IF NOT EXISTS products (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    sku VARCHAR(100) NOT NULL,
    asin VARCHAR(20),
    name VARCHAR(500) NOT NULL,
    image_url TEXT,
    price DECIMAL(18,4) DEFAULT 0,
    cost DECIMAL(18,4) DEFAULT 0,
    gross_margin DECIMAL(10,6) DEFAULT 0,
    inventory INT DEFAULT 0,
    target_acos DECIMAL(10,6) DEFAULT 0,
    break_even_acos DECIMAL(10,6) DEFAULT 0,
    category VARCHAR(255),
    brand VARCHAR(255),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'paused', 'out_of_stock')),
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(store_id, sku)
);

-- Product Cost Rules
CREATE TABLE IF NOT EXISTS product_costs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) REFERENCES products(id),
    cost_type VARCHAR(100) NOT NULL,
    allocation_method VARCHAR(50) DEFAULT 'fixed' CHECK (allocation_method IN ('fixed', 'percentage', 'per_unit', 'by_weight', 'by_volume', 'manual')),
    value DECIMAL(18,4) DEFAULT 0,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Product Images
CREATE TABLE IF NOT EXISTS product_images (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    sort_order INT DEFAULT 0,
    is_primary TINYINT(1) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('stores', 'idx_stores_org', '(org_id)');
CALL adpilot_create_index_if_missing('stores', 'idx_stores_marketplace', '(marketplace_id)');
CALL adpilot_create_index_if_missing('stores', 'idx_stores_status', '(status)');
-- platform-workspace-rbac (Req 10.2, 18): ensure the first-class store-group
-- columns exist on a pre-existing `stores` table (CREATE TABLE IF NOT EXISTS
-- above is a no-op when the table already exists), so re-import is clean and
-- the migration backfill at the end of this file can run idempotently.
CALL adpilot_add_column_if_missing('stores', 'store_group_id', 'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('stores', 'platform_family', 'VARCHAR(20) NULL');
CALL adpilot_create_index_if_missing('stores', 'idx_stores_store_group', '(store_group_id)');
CALL adpilot_create_index_if_missing('stores', 'idx_stores_platform_family', '(platform_family)');
CALL adpilot_create_index_if_missing('products', 'idx_products_store', '(store_id)');
CALL adpilot_create_index_if_missing('products', 'idx_products_sku', '(sku)');
CALL adpilot_create_index_if_missing('products', 'idx_products_asin', '(asin)');
CALL adpilot_create_index_if_missing('products', 'idx_products_status', '(status)');
CALL adpilot_create_index_if_missing('product_costs', 'idx_product_costs_store', '(store_id)');
CALL adpilot_create_index_if_missing('product_costs', 'idx_product_costs_product', '(product_id)');
CALL adpilot_create_index_if_missing('product_images', 'idx_product_images_product', '(product_id)');
-- Operation Tasks
CREATE TABLE IF NOT EXISTS operation_tasks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) REFERENCES stores(id),
    title VARCHAR(500) NOT NULL,
    description TEXT,
    task_type VARCHAR(50) NOT NULL CHECK (task_type IN ('ads', 'keyword', 'listing', 'inventory', 'profit', 'upload', 'competitor', 'data_quality', 'finance', 'approval', 'procurement', 'warehouse', 'logistics', 'order', 'customer', 'review')),
    source_type VARCHAR(50) DEFAULT 'manual' CHECK (source_type IN ('ai', 'rule', 'manual', 'import', 'feishu', 'system')),
    related_entity_type VARCHAR(50),
    related_entity_id CHAR(36),
    priority VARCHAR(20) DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'urgent')),
    risk_level VARCHAR(20) DEFAULT 'low',
    status VARCHAR(30) DEFAULT 'open' CHECK (status IN ('open', 'in_progress', 'waiting_approval', 'completed', 'dismissed', 'failed')),
    assigned_to_user_id CHAR(36) REFERENCES users(id),
    due_date DATETIME(3),
    expected_impact TEXT,
    suggested_action TEXT,
    approval_required TINYINT(1) DEFAULT 0,
    completed_at DATETIME(3),
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Reports
CREATE TABLE IF NOT EXISTS reports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) REFERENCES stores(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('daily', 'weekly', 'monthly', 'custom')),
    title VARCHAR(500) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    data JSON DEFAULT (JSON_OBJECT()),
    summary TEXT,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- API Connections
CREATE TABLE IF NOT EXISTS api_connections (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    provider VARCHAR(50) NOT NULL CHECK (provider IN ('amazon', 'shopify', 'tiktok', 'meta', 'walmart', 'google')),
    access_token TEXT, -- encrypted in application layer
    refresh_token TEXT, -- encrypted in application layer
    expires_at DATETIME(3),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'expired', 'error')),
    last_sync_at DATETIME(3),
    settings JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_store', '(store_id)');
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_status', '(status)');
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_priority', '(priority)');
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_assigned', '(assigned_to_user_id)');
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_type', '(task_type)');
CALL adpilot_create_index_if_missing('operation_tasks', 'idx_tasks_due', '(due_date)');
CALL adpilot_create_index_if_missing('reports', 'idx_reports_store', '(store_id)');
CALL adpilot_create_index_if_missing('reports', 'idx_reports_type', '(type)');
CALL adpilot_create_index_if_missing('api_connections', 'idx_api_connections_store', '(store_id)');
-- Goals
CREATE TABLE IF NOT EXISTS goals (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('launch', 'profit', 'growth', 'brand_defense', 'competitor', 'category', 'clearance', 'rank_boost')),
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'paused', 'completed')),
    target_acos DECIMAL(10,6) DEFAULT 0,
    daily_budget DECIMAL(18,4) DEFAULT 0,
    max_cpc DECIMAL(10,4) DEFAULT 0,
    min_bid DECIMAL(10,4) DEFAULT 0,
    max_bid DECIMAL(10,4) DEFAULT 0,
    brand_keywords JSON DEFAULT (JSON_ARRAY()),
    category_keywords JSON DEFAULT (JSON_ARRAY()),
    competitor_brands JSON DEFAULT (JSON_ARRAY()),
    competitor_asins JSON DEFAULT (JSON_ARRAY()),
    auto_negate TINYINT(1) DEFAULT 1,
    auto_bid TINYINT(1) DEFAULT 1,
    auto_expand TINYINT(1) DEFAULT 1,
    optimize_frequency VARCHAR(20) DEFAULT 'daily',
    risk_preference VARCHAR(20) DEFAULT 'balanced',
    product_ids JSON DEFAULT (JSON_ARRAY()),
    -- advertising-workspace-rework (Req 5.4): optimistic-lock version
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Campaigns
CREATE TABLE IF NOT EXISTS campaigns (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    goal_id CHAR(36) REFERENCES goals(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50),
    campaign_type VARCHAR(50),
    portfolio VARCHAR(100),
    status VARCHAR(30) DEFAULT 'active',
    budget DECIMAL(10,2),
    budget_type VARCHAR(20) DEFAULT 'daily',
    daily_budget DECIMAL(18,4) DEFAULT 0,
    budget_used_today DECIMAL(18,4) DEFAULT 0,
    start_date VARCHAR(10),
    end_date VARCHAR(10),
    targeting_type VARCHAR(30),
    state VARCHAR(20),
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    acos DECIMAL(10,4) DEFAULT 0,
    roas DECIMAL(10,4) DEFAULT 0,
    conversion_rate DECIMAL(10,4) DEFAULT 0,
    avg_cpc DECIMAL(10,4) DEFAULT 0,
    ad_group_count INT DEFAULT 0,
    keyword_count INT DEFAULT 0,
    negative_keyword_count INT DEFAULT 0,
    external_id VARCHAR(100),
    tags JSON,
    -- advertising-workspace-rework: campaign-level AI_Personality override (Req 49.2),
    -- immutable origin local/amazon_import (Req 12.6), Amazon-assigned campaign id
    -- gating synced-list inclusion (Req 12.7), optimistic-lock version (Req 5.4).
    campaign_personality VARCHAR(20),
    origin VARCHAR(20) NOT NULL DEFAULT 'local',
    amazon_campaign_id VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Ad Groups
CREATE TABLE IF NOT EXISTS ad_groups (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    store_id CHAR(36),
    name VARCHAR(255) NOT NULL,
    default_bid DECIMAL(10,4) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    external_id VARCHAR(100),
    -- advertising-workspace-rework (Req 5.4): optimistic-lock version
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Keywords
CREATE TABLE IF NOT EXISTS keywords (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36),
    ad_group_id CHAR(36) NOT NULL REFERENCES ad_groups(id),
    store_id CHAR(36),
    keyword_text VARCHAR(500) NOT NULL,
    match_type VARCHAR(20),
    bid DECIMAL(10,4) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    bid_health_score INT DEFAULT 50,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    acos DECIMAL(10,4) DEFAULT 0,
    roas DECIMAL(10,4) DEFAULT 0,
    ctr DECIMAL(10,6) DEFAULT 0,
    cvr DECIMAL(10,6) DEFAULT 0,
    avg_cpc DECIMAL(10,4) DEFAULT 0,
    external_id VARCHAR(100),
    -- advertising-workspace-rework (Req 5.4): optimistic-lock version
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Targets (ASIN/Category/Brand targeting);
CREATE TABLE IF NOT EXISTS targets (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36) NOT NULL,
    ad_group_id CHAR(36) NOT NULL REFERENCES ad_groups(id),
    store_id CHAR(36) NOT NULL,
    targeting_type VARCHAR(30),
    targeting_value VARCHAR(500),
    status VARCHAR(20) DEFAULT 'active',
    bid DECIMAL(10,4) DEFAULT 0,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    acos DECIMAL(10,4) DEFAULT 0,
    ctr DECIMAL(10,6) DEFAULT 0,
    cvr DECIMAL(10,6) DEFAULT 0,
    avg_cpc DECIMAL(10,4) DEFAULT 0,
    external_id VARCHAR(100),
    -- advertising-workspace-rework (Req 5.4): optimistic-lock version
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Search Terms
CREATE TABLE IF NOT EXISTS search_terms (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    ad_group_id CHAR(36),
    keyword_id CHAR(36) REFERENCES keywords(id),
    store_id CHAR(36) NOT NULL,
    search_term VARCHAR(500) NOT NULL,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    orders INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    acos DECIMAL(10,6) DEFAULT 0,
    ctr DECIMAL(10,6) DEFAULT 0,
    cvr DECIMAL(10,6) DEFAULT 0,
    cpc DECIMAL(10,4) DEFAULT 0,
    avg_cpc DECIMAL(10,4) DEFAULT 0,
    roas DECIMAL(10,4) DEFAULT 0,
    harvested TINYINT(1) DEFAULT 0,
    harvesting_status VARCHAR(30) DEFAULT 'candidate' CHECK (harvesting_status IN ('candidate', 'add_exact', 'add_phrase', 'add_broad', 'add_negative', 'watchlist', 'waste')),
    period_start DATE,
    period_end DATE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    -- project-fix-and-cleanup (M4): search-term metrics are aggregated additively
    -- into ONE row per (campaign_id, ad_group_id, search_term). This unique grain
    -- lets ImportServiceImpl.upsertSearchTerm rely on the DB to reject a duplicate
    -- insert from a concurrent import (and fall back to re-select + update).
    CONSTRAINT uq_search_term_grain UNIQUE (campaign_id, ad_group_id, search_term)
);

-- Negative Keywords
CREATE TABLE IF NOT EXISTS negative_keywords (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36),
    ad_group_id CHAR(36),
    store_id CHAR(36) NOT NULL,
    keyword_text VARCHAR(500) NOT NULL,
    match_type VARCHAR(20),
    level VARCHAR(20) DEFAULT 'campaign',
    source VARCHAR(50) DEFAULT 'manual',
    status VARCHAR(20) DEFAULT 'enabled',
    external_id VARCHAR(100),
    -- advertising-workspace-rework (Req 5.4): optimistic-lock version
    version BIGINT NOT NULL DEFAULT 0,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Recommendations
CREATE TABLE IF NOT EXISTS recommendations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    campaign_id CHAR(36),
    keyword_id CHAR(36),
    target_id CHAR(36),
    type VARCHAR(50) NOT NULL,
    priority VARCHAR(10) DEFAULT 'medium',
    title VARCHAR(500) NOT NULL,
    description TEXT,
    reason TEXT,
    target_entity_type VARCHAR(50),
    target_entity_id CHAR(36),
    target_entity_name VARCHAR(500),
    current_value VARCHAR(255),
    recommended_value VARCHAR(255),
    estimated_impact DECIMAL(10,2),
    confidence DECIMAL(5,2),
    current_data JSON DEFAULT (JSON_OBJECT()),
    expected_impact TEXT,
    risk_level VARCHAR(20) DEFAULT 'low' CHECK (risk_level IN ('low', 'medium', 'high')),
    status VARCHAR(30) DEFAULT 'pending',
    applied_at DATETIME(3),
    dismissed_at DATETIME(3),
    applied_by CHAR(36),
    metadata JSON,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Bid Changes
CREATE TABLE IF NOT EXISTS bid_changes (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    keyword_id CHAR(36),
    target_id CHAR(36),
    campaign_id CHAR(36),
    keyword_text VARCHAR(500),
    entity_type VARCHAR(30),
    old_bid DECIMAL(10,4),
    new_bid DECIMAL(10,4),
    reason TEXT,
    change_reason VARCHAR(100),
    source VARCHAR(30) DEFAULT 'manual' CHECK (source IN ('manual', 'ai_suggested', 'auto_applied')),
    changed_by CHAR(36),
    is_automated TINYINT(1) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Budget Changes
CREATE TABLE IF NOT EXISTS budget_changes (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    campaign_name VARCHAR(255),
    old_budget DECIMAL(18,4),
    new_budget DECIMAL(18,4),
    reason TEXT,
    change_reason VARCHAR(100),
    source VARCHAR(30) DEFAULT 'manual' CHECK (source IN ('manual', 'ai_suggested', 'auto_applied')),
    changed_by CHAR(36),
    is_automated TINYINT(1) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Performance Daily
CREATE TABLE IF NOT EXISTS performance_daily (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    campaign_id CHAR(36),
    keyword_id CHAR(36),
    entity_type VARCHAR(30) NOT NULL CHECK (entity_type IN ('campaign', 'ad_group', 'keyword', 'target')),
    entity_id CHAR(36) NOT NULL,
    report_date DATE NOT NULL,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    orders INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    acos DECIMAL(10,6) DEFAULT 0,
    roas DECIMAL(10,6) DEFAULT 0,
    ctr DECIMAL(10,6) DEFAULT 0,
    cvr DECIMAL(10,6) DEFAULT 0,
    cpc DECIMAL(10,4) DEFAULT 0,
    avg_cpc DECIMAL(10,4) DEFAULT 0,
    currency VARCHAR(10) NULL,
    data_status VARCHAR(12) NOT NULL DEFAULT 'preliminary'
        CHECK (data_status IN ('preliminary', 'finalized')),
    data_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_perf_daily UNIQUE (store_id, entity_type, entity_id, report_date)
);

-- Indexes
CALL adpilot_create_index_if_missing('goals', 'idx_goals_store', '(store_id)');
CALL adpilot_create_index_if_missing('goals', 'idx_goals_status', '(status)');
CALL adpilot_create_index_if_missing('goals', 'idx_goals_type', '(type)');
CALL adpilot_create_index_if_missing('campaigns', 'idx_campaigns_goal', '(goal_id)');
CALL adpilot_create_index_if_missing('campaigns', 'idx_campaigns_store', '(store_id)');
CALL adpilot_create_index_if_missing('campaigns', 'idx_campaigns_status', '(status)');
CALL adpilot_create_index_if_missing('ad_groups', 'idx_ad_groups_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('keywords', 'idx_keywords_ad_group', '(ad_group_id)');
CALL adpilot_create_index_if_missing('keywords', 'idx_keywords_status', '(status)');
CALL adpilot_create_index_if_missing('keywords', 'idx_keywords_match_type', '(match_type)');
CALL adpilot_create_index_if_missing('targets', 'idx_targets_ad_group', '(ad_group_id)');
CALL adpilot_create_index_if_missing('targets', 'idx_targets_type', '(targeting_type)');
CALL adpilot_create_index_if_missing('search_terms', 'idx_search_terms_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('search_terms', 'idx_search_terms_status', '(harvesting_status)');
CALL adpilot_create_index_if_missing('search_terms', 'idx_search_terms_period', '(period_start)');
-- project-fix-and-cleanup (M4): enforce the additive search-term aggregation grain
-- on pre-existing tables too (CREATE TABLE above is a no-op when it already exists).
CALL adpilot_create_unique_index_if_missing('search_terms', 'uq_search_term_grain', '(campaign_id, ad_group_id, search_term)');
CALL adpilot_create_index_if_missing('negative_keywords', 'idx_negative_keywords_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('recommendations', 'idx_recommendations_store', '(store_id)');
CALL adpilot_create_index_if_missing('recommendations', 'idx_recommendations_status', '(status)');
CALL adpilot_create_index_if_missing('recommendations', 'idx_recommendations_risk', '(risk_level)');
CALL adpilot_create_index_if_missing('bid_changes', 'idx_bid_changes_keyword', '(keyword_id)');
CALL adpilot_create_index_if_missing('budget_changes', 'idx_budget_changes_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('performance_daily', 'idx_perf_daily_entity', '(entity_type, entity_id)');
CALL adpilot_create_index_if_missing('performance_daily', 'idx_perf_daily_date', '(report_date)');
CALL adpilot_create_index_if_missing('performance_daily', 'idx_perf_daily_store_date', '(store_id, report_date)');
CALL adpilot_create_index_if_missing('performance_daily', 'idx_perf_daily_campaign_date', '(campaign_id, report_date)');
-- Keyword Insights
CREATE TABLE IF NOT EXISTS keyword_insights (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) REFERENCES products(id),
    campaign_id CHAR(36) REFERENCES campaigns(id),
    keyword_id CHAR(36) REFERENCES keywords(id),
    search_term_id CHAR(36),
    text VARCHAR(500) NOT NULL,
    source VARCHAR(50) NOT NULL,
    segment VARCHAR(50) NOT NULL,
    health_score INT DEFAULT 50,
    opportunity_score INT DEFAULT 0,
    waste_score INT DEFAULT 0,
    confidence_score INT DEFAULT 50,
    recommended_action VARCHAR(100),
    reason TEXT,
    current_data JSON DEFAULT (JSON_OBJECT()),
    expected_impact TEXT,
    risk_level VARCHAR(20) DEFAULT 'low',
    status VARCHAR(30) DEFAULT 'pending',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Keyword Coverage
CREATE TABLE IF NOT EXISTS keyword_coverage (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    store_id CHAR(36) NOT NULL,
    campaign_id CHAR(36),
    keyword_id CHAR(36),
    keyword_text VARCHAR(500) NOT NULL,
    match_type VARCHAR(20),
    in_title TINYINT(1) DEFAULT 0,
    in_bullets TINYINT(1) DEFAULT 0,
    in_description TINYINT(1) DEFAULT 0,
    in_backend_search_terms TINYINT(1) DEFAULT 0,
    in_a_plus_content TINYINT(1) DEFAULT 0,
    is_in_listing TINYINT(1),
    is_in_title TINYINT(1),
    is_in_bullet_points TINYINT(1),
    is_in_backend TINYINT(1),
    impressions BIGINT,
    clicks BIGINT,
    orders BIGINT,
    spend DECIMAL(12,2),
    sales DECIMAL(12,2),
    acos DECIMAL(8,4),
    cvr DECIMAL(8,4),
    coverage_score INT DEFAULT 0,
    coverage_status VARCHAR(20),
    recommendation TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Keyword N-Grams
CREATE TABLE IF NOT EXISTS keyword_ngrams (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) REFERENCES products(id),
    ngram VARCHAR(200) NOT NULL,
    ngram_type VARCHAR(20) NOT NULL,
    frequency INT,
    impressions INT DEFAULT 0,
    clicks INT DEFAULT 0,
    orders INT DEFAULT 0,
    total_clicks BIGINT,
    total_orders BIGINT,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    total_spend DECIMAL(12,2),
    total_sales DECIMAL(12,2),
    acos DECIMAL(10,6) DEFAULT 0,
    roas DECIMAL(10,6) DEFAULT 0,
    avg_acos DECIMAL(8,4),
    avg_cvr DECIMAL(8,4),
    waste_count INT,
    winner_count INT,
    segment VARCHAR(50),
    recommended_action VARCHAR(100),
    recommendation TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Content
CREATE TABLE IF NOT EXISTS listing_contents (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) NULL REFERENCES marketplaces(id),
    title VARCHAR(500),
    bullet_points JSON DEFAULT (JSON_ARRAY()),
    description TEXT,
    backend_search_terms TEXT,
    product_highlights TEXT,
    attributes JSON DEFAULT (JSON_OBJECT()),
    subject_matter VARCHAR(500),
    intended_use VARCHAR(500),
    target_audience VARCHAR(500),
    search_terms TEXT,
    status VARCHAR(30) DEFAULT 'draft',
    listing_score INT DEFAULT 0,
    compliance_score INT DEFAULT 0,
    seo_score INT DEFAULT 0,
    conversion_score INT DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Drafts
CREATE TABLE IF NOT EXISTS listing_drafts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) NOT NULL REFERENCES marketplaces(id),
    source_type VARCHAR(50) DEFAULT 'ai_generated',
    content JSON DEFAULT (JSON_OBJECT()),
    validation_result JSON DEFAULT (JSON_OBJECT()),
    status VARCHAR(30) DEFAULT 'draft',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Versions
CREATE TABLE IF NOT EXISTS listing_versions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) NOT NULL REFERENCES marketplaces(id),
    version_number INT NOT NULL,
    title VARCHAR(500),
    bullet_points JSON DEFAULT (JSON_ARRAY()),
    description TEXT,
    backend_search_terms TEXT,
    listing_score INT DEFAULT 0,
    compliance_score INT DEFAULT 0,
    seo_score INT DEFAULT 0,
    changed_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Competitor Products
CREATE TABLE IF NOT EXISTS competitor_products (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    asin VARCHAR(20) NOT NULL,
    brand VARCHAR(255),
    title VARCHAR(500),
    price DECIMAL(18,4),
    rating DECIMAL(3,2),
    review_count INT DEFAULT 0,
    image_url TEXT,
    bullet_points JSON DEFAULT (JSON_ARRAY()),
    detected_keywords JSON DEFAULT (JSON_ARRAY()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('keyword_insights', 'idx_keyword_insights_store', '(store_id)');
CALL adpilot_create_index_if_missing('keyword_insights', 'idx_keyword_insights_product', '(product_id)');
CALL adpilot_create_index_if_missing('keyword_insights', 'idx_keyword_insights_segment', '(segment)');
CALL adpilot_create_index_if_missing('keyword_insights', 'idx_keyword_insights_status', '(status)');
CALL adpilot_create_index_if_missing('keyword_coverage', 'idx_keyword_coverage_product', '(product_id)');
CALL adpilot_create_index_if_missing('keyword_ngrams', 'idx_keyword_ngrams_store', '(store_id)');
CALL adpilot_create_index_if_missing('keyword_ngrams', 'idx_keyword_ngrams_ngram', '(ngram)');
CALL adpilot_create_index_if_missing('listing_contents', 'idx_listing_contents_product', '(product_id)');
CALL adpilot_create_index_if_missing('listing_drafts', 'idx_listing_drafts_product', '(product_id)');
CALL adpilot_create_index_if_missing('listing_drafts', 'idx_listing_drafts_status', '(status)');
CALL adpilot_create_index_if_missing('listing_versions', 'idx_listing_versions_product', '(product_id)');
CALL adpilot_create_index_if_missing('competitor_products', 'idx_competitor_products_product', '(product_id)');
CALL adpilot_create_index_if_missing('competitor_products', 'idx_competitor_products_asin', '(asin)');
-- Product Upload Jobs
CREATE TABLE IF NOT EXISTS product_upload_jobs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) NOT NULL REFERENCES marketplaces(id),
    upload_method VARCHAR(30) DEFAULT 'flat_file',
    status VARCHAR(30) DEFAULT 'draft',
    payload JSON DEFAULT (JSON_OBJECT()),
    response JSON DEFAULT (JSON_OBJECT()),
    error_message TEXT,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Import Jobs
CREATE TABLE IF NOT EXISTS import_jobs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    report_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(500),
    file_size BIGINT DEFAULT 0,
    status VARCHAR(30) DEFAULT 'uploaded',
    total_rows INT DEFAULT 0,
    valid_rows INT DEFAULT 0,
    invalid_rows INT DEFAULT 0,
    duplicate_rows INT DEFAULT 0,
    mapping_config JSON DEFAULT (JSON_OBJECT()),
    summary JSON DEFAULT (JSON_OBJECT()),
    error_message TEXT,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Import Row Errors
CREATE TABLE IF NOT EXISTS import_row_errors (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    import_job_id CHAR(36) NOT NULL REFERENCES import_jobs(id),
    `row_number` INT NOT NULL,
    raw_data JSON DEFAULT (JSON_OBJECT()),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Raw Search Term Reports
CREATE TABLE IF NOT EXISTS raw_search_term_reports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    import_job_id CHAR(36) NOT NULL REFERENCES import_jobs(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    report_date DATE,
    campaign_name VARCHAR(500),
    ad_group_name VARCHAR(500),
    targeting VARCHAR(500),
    match_type VARCHAR(50),
    customer_search_term VARCHAR(500),
    impressions INT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Raw Targeting Reports
CREATE TABLE IF NOT EXISTS raw_targeting_reports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    import_job_id CHAR(36) NOT NULL REFERENCES import_jobs(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    report_date DATE,
    campaign_name VARCHAR(500),
    ad_group_name VARCHAR(500),
    targeting VARCHAR(500),
    match_type VARCHAR(50),
    impressions INT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Raw Campaign Reports
CREATE TABLE IF NOT EXISTS raw_campaign_reports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    import_job_id CHAR(36) NOT NULL REFERENCES import_jobs(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    report_date DATE,
    campaign_name VARCHAR(500),
    campaign_type VARCHAR(100),
    impressions INT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    budget DECIMAL(18,4),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Raw Advertised Product Reports
CREATE TABLE IF NOT EXISTS raw_advertised_product_reports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    import_job_id CHAR(36) NOT NULL REFERENCES import_jobs(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    report_date DATE,
    campaign_name VARCHAR(500),
    ad_group_name VARCHAR(500),
    sku VARCHAR(100),
    asin VARCHAR(20),
    impressions INT DEFAULT 0,
    clicks INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    orders INT DEFAULT 0,
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Data Quality Issues
CREATE TABLE IF NOT EXISTS data_quality_issues (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    import_job_id CHAR(36) REFERENCES import_jobs(id),
    severity VARCHAR(20) NOT NULL,
    issue_type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    related_entity_type VARCHAR(50),
    related_entity_id CHAR(36),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    status VARCHAR(20) DEFAULT 'open',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('product_upload_jobs', 'idx_upload_jobs_store', '(store_id)');
CALL adpilot_create_index_if_missing('product_upload_jobs', 'idx_upload_jobs_status', '(status)');
CALL adpilot_create_index_if_missing('import_jobs', 'idx_import_jobs_store', '(store_id)');
CALL adpilot_create_index_if_missing('import_jobs', 'idx_import_jobs_status', '(status)');
CALL adpilot_create_index_if_missing('import_row_errors', 'idx_import_row_errors_job', '(import_job_id)');
CALL adpilot_create_index_if_missing('raw_search_term_reports', 'idx_raw_search_terms_job', '(import_job_id)');
CALL adpilot_create_index_if_missing('raw_search_term_reports', 'idx_raw_search_terms_store', '(store_id)');
CALL adpilot_create_index_if_missing('raw_search_term_reports', 'idx_raw_search_terms_date', '(report_date)');
CALL adpilot_create_index_if_missing('raw_targeting_reports', 'idx_raw_targeting_job', '(import_job_id)');
CALL adpilot_create_index_if_missing('raw_campaign_reports', 'idx_raw_campaign_job', '(import_job_id)');
CALL adpilot_create_index_if_missing('raw_advertised_product_reports', 'idx_raw_adv_product_job', '(import_job_id)');
CALL adpilot_create_index_if_missing('raw_advertised_product_reports', 'idx_raw_adv_product_sku', '(sku)');
CALL adpilot_create_index_if_missing('raw_advertised_product_reports', 'idx_raw_adv_product_asin', '(asin)');
CALL adpilot_create_index_if_missing('data_quality_issues', 'idx_data_quality_store', '(store_id)');
CALL adpilot_create_index_if_missing('data_quality_issues', 'idx_data_quality_status', '(status)');
-- Product Profit Daily
CREATE TABLE IF NOT EXISTS product_profit_daily (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    date DATE NOT NULL,
    sku VARCHAR(100),
    asin VARCHAR(20),
    units_sold INT DEFAULT 0,
    gross_sales DECIMAL(18,4) DEFAULT 0,
    organic_sales DECIMAL(18,4) DEFAULT 0,
    ad_sales DECIMAL(18,4) DEFAULT 0,
    ad_spend DECIMAL(18,4) DEFAULT 0,
    amazon_referral_fee DECIMAL(18,4) DEFAULT 0,
    fba_fee DECIMAL(18,4) DEFAULT 0,
    storage_fee DECIMAL(18,4) DEFAULT 0,
    refund_cost DECIMAL(18,4) DEFAULT 0,
    promo_cost DECIMAL(18,4) DEFAULT 0,
    cogs DECIMAL(18,4) DEFAULT 0,
    inbound_shipping_cost DECIMAL(18,4) DEFAULT 0,
    other_cost DECIMAL(18,4) DEFAULT 0,
    gross_profit DECIMAL(18,4) DEFAULT 0,
    net_profit DECIMAL(18,4) DEFAULT 0,
    gross_margin DECIMAL(10,6) DEFAULT 0,
    net_margin DECIMAL(10,6) DEFAULT 0,
    acos DECIMAL(10,6) DEFAULT 0,
    tacos DECIMAL(10,6) DEFAULT 0,
    roas DECIMAL(10,6) DEFAULT 0,
    break_even_acos DECIMAL(10,6) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(store_id, product_id, date)
);

-- Cost Rules
CREATE TABLE IF NOT EXISTS cost_rules (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) REFERENCES products(id),
    cost_type VARCHAR(100) NOT NULL,
    allocation_method VARCHAR(30) DEFAULT 'fixed',
    value DECIMAL(18,4) DEFAULT 0,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Settlement Imports
CREATE TABLE IF NOT EXISTS settlement_imports (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    file_name VARCHAR(500),
    status VARCHAR(30) DEFAULT 'uploaded',
    period_start DATE,
    period_end DATE,
    total_amount DECIMAL(18,4) DEFAULT 0,
    summary JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Inventory Snapshots
CREATE TABLE IF NOT EXISTS inventory_snapshots (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    marketplace_id CHAR(36) REFERENCES marketplaces(id),
    snapshot_date DATE NOT NULL,
    available_inventory INT DEFAULT 0,
    reserved_inventory INT DEFAULT 0,
    inbound_inventory INT DEFAULT 0,
    transfer_inventory INT DEFAULT 0,
    unsellable_inventory INT DEFAULT 0,
    total_inventory INT DEFAULT 0,
    inventory_value DECIMAL(18,4) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(store_id, product_id, snapshot_date)
);

-- Inventory Forecasts
CREATE TABLE IF NOT EXISTS inventory_forecasts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    forecast_date DATE NOT NULL,
    daily_sales_velocity DECIMAL(10,4) DEFAULT 0,
    ad_driven_sales_velocity DECIMAL(10,4) DEFAULT 0,
    organic_sales_velocity DECIMAL(10,4) DEFAULT 0,
    days_of_supply INT DEFAULT 0,
    stockout_date DATE,
    stockout_risk VARCHAR(20) DEFAULT 'low',
    overstock_risk VARCHAR(20) DEFAULT 'low',
    recommended_replenishment_qty INT DEFAULT 0,
    reorder_point INT DEFAULT 0,
    safety_stock INT DEFAULT 0,
    lead_time_days INT DEFAULT 14,
    confidence_score INT DEFAULT 50,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(store_id, product_id, forecast_date)
);

-- Replenishment Plans
CREATE TABLE IF NOT EXISTS replenishment_plans (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NOT NULL REFERENCES products(id),
    status VARCHAR(30) DEFAULT 'draft',
    recommended_qty INT DEFAULT 0,
    approved_qty INT,
    reason TEXT,
    expected_stockout_date DATE,
    expected_arrival_date DATE,
    purchase_cost DECIMAL(18,4),
    shipping_cost DECIMAL(18,4),
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_by CHAR(36) REFERENCES users(id),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('product_profit_daily', 'idx_profit_daily_store', '(store_id)');
CALL adpilot_create_index_if_missing('product_profit_daily', 'idx_profit_daily_product', '(product_id)');
CALL adpilot_create_index_if_missing('product_profit_daily', 'idx_profit_daily_date', '(date)');
CALL adpilot_create_index_if_missing('cost_rules', 'idx_cost_rules_store', '(store_id)');
CALL adpilot_create_index_if_missing('cost_rules', 'idx_cost_rules_product', '(product_id)');
CALL adpilot_create_index_if_missing('settlement_imports', 'idx_settlement_imports_store', '(store_id)');
CALL adpilot_create_index_if_missing('inventory_snapshots', 'idx_inventory_snapshots_store', '(store_id)');
CALL adpilot_create_index_if_missing('inventory_snapshots', 'idx_inventory_snapshots_product', '(product_id)');
CALL adpilot_create_index_if_missing('inventory_snapshots', 'idx_inventory_snapshots_date', '(snapshot_date)');
CALL adpilot_create_index_if_missing('inventory_forecasts', 'idx_inventory_forecasts_store', '(store_id)');
CALL adpilot_create_index_if_missing('inventory_forecasts', 'idx_inventory_forecasts_product', '(product_id)');
CALL adpilot_create_index_if_missing('inventory_forecasts', 'idx_inventory_forecasts_risk', '(stockout_risk)');
CALL adpilot_create_index_if_missing('replenishment_plans', 'idx_replenishment_store', '(store_id)');
CALL adpilot_create_index_if_missing('replenishment_plans', 'idx_replenishment_product', '(product_id)');
CALL adpilot_create_index_if_missing('replenishment_plans', 'idx_replenishment_status', '(status)');
CREATE TABLE IF NOT EXISTS approval_requests (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    -- V10: store-centric columns relaxed to NULLABLE so policy-gated rows
    -- created by the ApprovalAspect (which only know module/action/initiator)
    -- are valid. Existing application paths still populate these.
    store_id CHAR(36) NULL REFERENCES stores(id),
    requester_id CHAR(36) NULL REFERENCES users(id),
    approver_id CHAR(36) REFERENCES users(id),
    request_type VARCHAR(100) NULL,
    related_entity_type VARCHAR(100),
    related_entity_id CHAR(36),
    title VARCHAR(500) NULL,
    description TEXT,
    payload JSON DEFAULT (JSON_OBJECT()),
    risk_level VARCHAR(20) DEFAULT 'low',
    status VARCHAR(30) DEFAULT 'pending',
    rejection_reason TEXT,
    resolved_at DATETIME(3),
    expires_at DATETIME(3),
    -- V10: generic policy-driven governed-action columns used by
    -- @RequiresApproval / ApprovalAspect.
    policy_id CHAR(36) NULL,
    module VARCHAR(100) NULL,
    action_type VARCHAR(100) NULL,
    amount DECIMAL(18,4) NULL,
    initiated_by CHAR(36) NULL,
    current_level INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- V10: approval_requests indexes for the policy-driven governed-action lookups
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_module_action', '(module, action_type)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_initiated_by', '(initiated_by)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_policy', '(policy_id)');

-- project-fix-and-cleanup: reconcile the V10 policy-driven governed-action
-- columns onto a pre-existing approval_requests table so a stale DB upgrades on
-- re-import (these were folded into CREATE TABLE with no add-column guard).
CALL adpilot_add_column_if_missing('approval_requests', 'policy_id', 'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('approval_requests', 'module', 'VARCHAR(100) NULL');
CALL adpilot_add_column_if_missing('approval_requests', 'action_type', 'VARCHAR(100) NULL');
CALL adpilot_add_column_if_missing('approval_requests', 'amount', 'DECIMAL(18,4) NULL');
CALL adpilot_add_column_if_missing('approval_requests', 'initiated_by', 'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('approval_requests', 'current_level', 'INT NOT NULL DEFAULT 1');

CREATE TABLE IF NOT EXISTS approval_watchers (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    approval_request_id CHAR(36) NOT NULL REFERENCES approval_requests(id) ON DELETE CASCADE,
    user_id CHAR(36) NOT NULL REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(approval_request_id, user_id)
);

-- V10: per-level approval/rejection decisions (routing & sequencing handled by ApprovalService)
CREATE TABLE IF NOT EXISTS approval_decisions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    request_id CHAR(36) NOT NULL REFERENCES approval_requests(id),
    level INT NOT NULL,
    approver_id CHAR(36) NOT NULL,
    decision VARCHAR(20) NOT NULL,          -- approved|rejected
    comment TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('approval_decisions', 'idx_approval_decisions_request', '(request_id)');

CREATE TABLE IF NOT EXISTS feishu_integrations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    store_id CHAR(36),
    owner_account_id CHAR(36) NULL,
    provider VARCHAR(50) DEFAULT 'feishu',
    connection_type VARCHAR(20) DEFAULT 'app',
    app_id VARCHAR(255) NULL,
    app_secret_encrypted TEXT NULL,
    webhook_url_encrypted TEXT,
    webhook_secret_encrypted TEXT,
    verification_token VARCHAR(255),
    verification_token_encrypted VARCHAR(255),
    encrypt_key VARCHAR(255),
    encrypt_key_encrypted VARCHAR(255),
    bot_name VARCHAR(255),
    bot_avatar_url VARCHAR(1000),
    bot_open_id VARCHAR(255),
    default_chat_id VARCHAR(255),
    status VARCHAR(30) DEFAULT 'active',
    event_callback_url VARCHAR(1000),
    permissions JSON DEFAULT (JSON_ARRAY()),
    last_connected_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS feishu_chat_bindings (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    feishu_integration_id CHAR(36) NOT NULL REFERENCES feishu_integrations(id) ON DELETE CASCADE,
    store_id CHAR(36) REFERENCES stores(id),
    chat_id VARCHAR(255) NOT NULL,
    chat_type VARCHAR(50) DEFAULT 'group',
    chat_name VARCHAR(500),
    notify_on_approval TINYINT(1) DEFAULT 1,
    notify_on_execution TINYINT(1) DEFAULT 1,
    notify_on_rollback TINYINT(1) DEFAULT 1,
    notify_on_risk_alert TINYINT(1) DEFAULT 1,
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS feishu_user_bindings (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    feishu_integration_id CHAR(36) NOT NULL REFERENCES feishu_integrations(id) ON DELETE CASCADE,
    user_id CHAR(36) NOT NULL REFERENCES users(id),
    feishu_user_id VARCHAR(255) NOT NULL,
    feishu_open_id VARCHAR(255),
    feishu_name VARCHAR(255),
    feishu_avatar_url VARCHAR(1000),
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS feishu_message_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    feishu_integration_id CHAR(36) NOT NULL REFERENCES feishu_integrations(id),
    chat_id VARCHAR(255),
    message_id VARCHAR(255),
    message_type VARCHAR(50) DEFAULT 'interactive',
    direction VARCHAR(20) NOT NULL,
    content JSON DEFAULT (JSON_OBJECT()),
    related_entity_type VARCHAR(100),
    related_entity_id CHAR(36),
    status VARCHAR(30) DEFAULT 'sent',
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS feishu_action_requests (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    feishu_integration_id CHAR(36) NOT NULL REFERENCES feishu_integrations(id),
    feishu_user_id VARCHAR(255),
    chat_id VARCHAR(255),
    message_id VARCHAR(255),
    action_type VARCHAR(100) NOT NULL,
    action_data JSON DEFAULT (JSON_OBJECT()),
    related_entity_type VARCHAR(100),
    related_entity_id CHAR(36),
    status VARCHAR(30) DEFAULT 'pending',
    approval_request_id CHAR(36) REFERENCES approval_requests(id),
    result JSON DEFAULT (JSON_OBJECT()),
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS automation_policies (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    store_id CHAR(36) REFERENCES stores(id),
    mode VARCHAR(30) DEFAULT 'manual',
    max_bid_change_pct INT DEFAULT 20,
    max_budget_change_pct INT DEFAULT 30,
    min_clicks_before_negative INT DEFAULT 20,
    block_brand_negative TINYINT(1) DEFAULT 1,
    block_competitor_in_listing TINYINT(1) DEFAULT 1,
    inventory_threshold INT DEFAULT 50,
    min_days_of_supply_to_scale INT DEFAULT 30,
    require_approval_for_high_risk TINYINT(1) DEFAULT 1,
    require_approval_for_product_upload TINYINT(1) DEFAULT 1,
    require_approval_for_replenishment TINYINT(1) DEFAULT 1,
    daily_budget_limit DECIMAL(18,4),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS automation_executions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    source VARCHAR(50) DEFAULT 'app',
    entity_type VARCHAR(100) NOT NULL,
    entity_id CHAR(36),
    action_type VARCHAR(100) NOT NULL,
    before_snapshot JSON DEFAULT (JSON_OBJECT()),
    after_snapshot JSON DEFAULT (JSON_OBJECT()),
    risk_level VARCHAR(20) DEFAULT 'low',
    approval_request_id CHAR(36) REFERENCES approval_requests(id),
    task_id CHAR(36),
    status VARCHAR(30) DEFAULT 'pending',
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS risk_evaluations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    action_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id CHAR(36),
    risk_level VARCHAR(20) DEFAULT 'low',
    reasons JSON DEFAULT (JSON_ARRAY()),
    blocked TINYINT(1) DEFAULT FALSE,
    requires_approval TINYINT(1) DEFAULT FALSE,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS rollback_plans (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    automation_execution_id CHAR(36) NOT NULL REFERENCES automation_executions(id),
    entity_type VARCHAR(100) NOT NULL,
    entity_id CHAR(36),
    rollback_data JSON DEFAULT (JSON_OBJECT()),
    status VARCHAR(30) DEFAULT 'available',
    expires_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS ai_model_call_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) REFERENCES users(id),
    store_id CHAR(36) REFERENCES stores(id),
    feature VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_snapshot TEXT,
    input_snapshot JSON DEFAULT (JSON_OBJECT()),
    output_snapshot JSON DEFAULT (JSON_OBJECT()),
    status VARCHAR(30) DEFAULT 'success',
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Approval indexes
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_store', '(store_id)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_requester', '(requester_id)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_approver', '(approver_id)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_status', '(status)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_risk', '(risk_level)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_type', '(request_type)');
CALL adpilot_create_index_if_missing('approval_requests', 'idx_approval_requests_expires', '(expires_at)');
CALL adpilot_create_index_if_missing('approval_watchers', 'idx_approval_watchers_request', '(approval_request_id)');
-- Feishu indexes
CALL adpilot_create_index_if_missing('feishu_integrations', 'idx_feishu_integrations_org', '(org_id)');
-- multistore-ai-ads-operations (Req 7.1): ensure per-account ownership column
-- exists on a pre-existing `feishu_integrations` table (the CREATE TABLE IF NOT
-- EXISTS above is a no-op when the table already exists), enabling per-account
-- isolation of Feishu notifications.
CALL adpilot_add_column_if_missing('feishu_integrations', 'owner_account_id', 'CHAR(36) NULL');
CALL adpilot_create_index_if_missing('feishu_integrations', 'idx_feishu_integrations_owner', '(owner_account_id, store_id)');
CALL adpilot_create_index_if_missing('feishu_chat_bindings', 'idx_feishu_chat_bindings_integration', '(feishu_integration_id)');
CALL adpilot_create_index_if_missing('feishu_chat_bindings', 'idx_feishu_chat_bindings_store', '(store_id)');
CALL adpilot_create_index_if_missing('feishu_user_bindings', 'idx_feishu_user_bindings_integration', '(feishu_integration_id)');
CALL adpilot_create_index_if_missing('feishu_user_bindings', 'idx_feishu_user_bindings_user', '(user_id)');
CALL adpilot_create_index_if_missing('feishu_message_logs', 'idx_feishu_message_logs_integration', '(feishu_integration_id)');
CALL adpilot_create_index_if_missing('feishu_message_logs', 'idx_feishu_message_logs_created', '(created_at)');
CALL adpilot_create_index_if_missing('feishu_action_requests', 'idx_feishu_action_requests_integration', '(feishu_integration_id)');
CALL adpilot_create_index_if_missing('feishu_action_requests', 'idx_feishu_action_requests_status', '(status)');
-- Automation indexes
CALL adpilot_create_index_if_missing('automation_policies', 'idx_automation_policies_org', '(org_id)');
CALL adpilot_create_index_if_missing('automation_policies', 'idx_automation_policies_store', '(store_id)');
CALL adpilot_create_index_if_missing('automation_executions', 'idx_automation_executions_store', '(store_id)');
CALL adpilot_create_index_if_missing('automation_executions', 'idx_automation_executions_status', '(status)');
CALL adpilot_create_index_if_missing('automation_executions', 'idx_automation_executions_entity', '(entity_type, entity_id)');
CALL adpilot_create_index_if_missing('automation_executions', 'idx_automation_executions_approval', '(approval_request_id)');
CALL adpilot_create_index_if_missing('risk_evaluations', 'idx_risk_evaluations_store', '(store_id)');
CALL adpilot_create_index_if_missing('risk_evaluations', 'idx_risk_evaluations_entity', '(entity_type, entity_id)');
CALL adpilot_create_index_if_missing('risk_evaluations', 'idx_risk_evaluations_created', '(created_at)');
-- Rollback indexes
CALL adpilot_create_index_if_missing('rollback_plans', 'idx_rollback_plans_execution', '(automation_execution_id)');
CALL adpilot_create_index_if_missing('rollback_plans', 'idx_rollback_plans_status', '(status)');
CALL adpilot_create_index_if_missing('rollback_plans', 'idx_rollback_plans_expires', '(expires_at)');
-- AI audit indexes
CALL adpilot_create_index_if_missing('ai_model_call_logs', 'idx_ai_model_call_logs_user', '(user_id)');
CALL adpilot_create_index_if_missing('ai_model_call_logs', 'idx_ai_model_call_logs_store', '(store_id)');
CALL adpilot_create_index_if_missing('ai_model_call_logs', 'idx_ai_model_call_logs_feature', '(feature)');
CALL adpilot_create_index_if_missing('ai_model_call_logs', 'idx_ai_model_call_logs_model', '(model)');
CALL adpilot_create_index_if_missing('ai_model_call_logs', 'idx_ai_model_call_logs_created', '(created_at)');
-- Orders
CREATE TABLE IF NOT EXISTS orders (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    order_id VARCHAR(255) NOT NULL,
    order_item_id VARCHAR(255),
    purchase_date DATETIME(3),
    last_update_date DATETIME(3),
    order_status VARCHAR(50),
    fulfillment_channel VARCHAR(50),
    sales_channel VARCHAR(50),
    marketplace_id VARCHAR(50),
    sku VARCHAR(100),
    asin VARCHAR(20),
    product_name VARCHAR(500),
    quantity_ordered INT DEFAULT 0,
    item_price DECIMAL(18,4) DEFAULT 0,
    item_tax DECIMAL(18,4) DEFAULT 0,
    shipping_price DECIMAL(18,4) DEFAULT 0,
    shipping_tax DECIMAL(18,4) DEFAULT 0,
    item_promotion_discount DECIMAL(18,4) DEFAULT 0,
    ship_promotion_discount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    buyer_email VARCHAR(255),
    recipient_name VARCHAR(255),
    ship_address_line1 VARCHAR(500),
    ship_city VARCHAR(255),
    ship_state VARCHAR(100),
    ship_postal_code VARCHAR(50),
    ship_country VARCHAR(50),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Returns
CREATE TABLE IF NOT EXISTS returns (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    order_id VARCHAR(255),
    return_id VARCHAR(255),
    sku VARCHAR(100),
    asin VARCHAR(20),
    quantity_returned INT DEFAULT 0,
    return_reason VARCHAR(255),
    return_status VARCHAR(50),
    return_date DATETIME(3),
    refund_amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Refunds
CREATE TABLE IF NOT EXISTS refunds (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    order_id VARCHAR(255),
    refund_id VARCHAR(255),
    sku VARCHAR(100),
    asin VARCHAR(20),
    refund_amount DECIMAL(18,4) DEFAULT 0,
    refund_reason VARCHAR(255),
    refund_status VARCHAR(50),
    refund_date DATETIME(3),
    currency VARCHAR(10),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Settlements
CREATE TABLE IF NOT EXISTS settlements (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    settlement_id VARCHAR(255),
    settlement_start_date DATE,
    settlement_end_date DATE,
    deposit_date DATE,
    total_amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    status VARCHAR(30) DEFAULT 'pending',
    -- V7: multi-currency conversion provenance (Req 9.2.2 / 9.2.6)
    reporting_currency VARCHAR(10) NULL,
    converted_amount DECIMAL(18,4) NULL,
    exchange_rate DECIMAL(18,8) NULL,
    rate_effective_date DATE NULL,
    -- V7: reconciliation outcome (NULL=not reconciled|matched|discrepancy) (Req 9.1.3)
    reconciliation_status VARCHAR(20) NULL,
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Settlement Transactions
CREATE TABLE IF NOT EXISTS settlement_transactions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    settlement_id CHAR(36) NOT NULL REFERENCES settlements(id),
    order_id VARCHAR(255),
    sku VARCHAR(100),
    transaction_type VARCHAR(100),
    amount DECIMAL(18,4) DEFAULT 0,
    fee_type VARCHAR(100),
    fee_amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Buyer Messages
CREATE TABLE IF NOT EXISTS buyer_messages (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    order_id VARCHAR(255),
    buyer_email VARCHAR(255),
    subject VARCHAR(500),
    message TEXT,
    direction VARCHAR(20) DEFAULT 'inbound',
    status VARCHAR(30) DEFAULT 'unread',
    reply TEXT,
    replied_at DATETIME(3),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('orders', 'idx_orders_store', '(store_id)');
CALL adpilot_create_index_if_missing('orders', 'idx_orders_order_id', '(order_id)');
CALL adpilot_create_index_if_missing('orders', 'idx_orders_sku', '(sku)');
CALL adpilot_create_index_if_missing('orders', 'idx_orders_asin', '(asin)');
CALL adpilot_create_index_if_missing('orders', 'idx_orders_date', '(purchase_date)');
CALL adpilot_create_index_if_missing('returns', 'idx_returns_store', '(store_id)');
CALL adpilot_create_index_if_missing('returns', 'idx_returns_order', '(order_id)');
CALL adpilot_create_index_if_missing('refunds', 'idx_refunds_store', '(store_id)');
CALL adpilot_create_index_if_missing('refunds', 'idx_refunds_order', '(order_id)');
CALL adpilot_create_index_if_missing('settlements', 'idx_settlements_store', '(store_id)');
-- project-fix-and-cleanup: reconcile the V7 multi-currency provenance +
-- reconciliation columns onto a pre-existing settlements table (stale-DB upgrade).
CALL adpilot_add_column_if_missing('settlements', 'reporting_currency', 'VARCHAR(10) NULL');
CALL adpilot_add_column_if_missing('settlements', 'converted_amount', 'DECIMAL(18,4) NULL');
CALL adpilot_add_column_if_missing('settlements', 'exchange_rate', 'DECIMAL(18,8) NULL');
CALL adpilot_add_column_if_missing('settlements', 'rate_effective_date', 'DATE NULL');
CALL adpilot_add_column_if_missing('settlements', 'reconciliation_status', 'VARCHAR(20) NULL');
CALL adpilot_create_index_if_missing('settlement_transactions', 'idx_settlement_transactions_settlement', '(settlement_id)');
CALL adpilot_create_index_if_missing('buyer_messages', 'idx_buyer_messages_store', '(store_id)');
CALL adpilot_create_index_if_missing('buyer_messages', 'idx_buyer_messages_order', '(order_id)');
CALL adpilot_create_index_if_missing('buyer_messages', 'idx_buyer_messages_status', '(status)');
-- Suppliers
CREATE TABLE IF NOT EXISTS suppliers (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    supplier_name VARCHAR(500) NOT NULL,
    contact_name VARCHAR(255),
    contact_email VARCHAR(255),
    contact_phone VARCHAR(50),
    address TEXT,
    country VARCHAR(50),
    payment_terms VARCHAR(100),
    lead_time_days INT,
    rating DECIMAL(3,2),
    status VARCHAR(30) DEFAULT 'active',
    notes TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Purchase Orders
CREATE TABLE IF NOT EXISTS purchase_orders (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    supplier_id CHAR(36) NOT NULL REFERENCES suppliers(id),
    po_number VARCHAR(100) NOT NULL,
    status VARCHAR(30) DEFAULT 'draft',
    total_amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    order_date DATE,
    expected_delivery_date DATE,
    actual_delivery_date DATE,
    shipping_method VARCHAR(100),
    tracking_number VARCHAR(255),
    notes TEXT,
    created_by CHAR(36),
    approved_by CHAR(36),
    approved_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Purchase Order Items
CREATE TABLE IF NOT EXISTS purchase_order_items (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    purchase_order_id CHAR(36) NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    sku VARCHAR(100),
    asin VARCHAR(20),
    product_name VARCHAR(500),
    quantity_ordered INT NOT NULL DEFAULT 0,
    quantity_received INT DEFAULT 0,
    unit_cost DECIMAL(18,4) DEFAULT 0,
    total_cost DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    notes TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Warehouse Locations
CREATE TABLE IF NOT EXISTS warehouse_locations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    location_name VARCHAR(255) NOT NULL,
    location_code VARCHAR(50),
    location_type VARCHAR(50),
    address TEXT,
    country VARCHAR(50),
    capacity INT,
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Warehouse Inventory
CREATE TABLE IF NOT EXISTS warehouse_inventory (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    warehouse_location_id CHAR(36) NOT NULL REFERENCES warehouse_locations(id),
    sku VARCHAR(100) NOT NULL,
    asin VARCHAR(20),
    product_name VARCHAR(500),
    quantity_on_hand INT DEFAULT 0,
    quantity_reserved INT DEFAULT 0,
    quantity_available INT DEFAULT 0,
    reorder_point INT DEFAULT 0,
    reorder_quantity INT DEFAULT 0,
    unit_cost DECIMAL(18,4) DEFAULT 0,
    last_counted_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(warehouse_location_id, sku)
);

-- Replenishment Requests
CREATE TABLE IF NOT EXISTS replenishment_requests (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    sku VARCHAR(100) NOT NULL,
    asin VARCHAR(20),
    product_name VARCHAR(500),
    current_quantity INT DEFAULT 0,
    recommended_quantity INT DEFAULT 0,
    approved_quantity INT,
    status VARCHAR(30) DEFAULT 'pending',
    priority VARCHAR(20) DEFAULT 'normal',
    source VARCHAR(50),
    purchase_order_id CHAR(36) REFERENCES purchase_orders(id),
    requested_by CHAR(36),
    approved_by CHAR(36),
    notes TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Inventory Movements
CREATE TABLE IF NOT EXISTS inventory_movements (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    warehouse_location_id CHAR(36) NOT NULL REFERENCES warehouse_locations(id),
    sku VARCHAR(100) NOT NULL,
    movement_type VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    reference_type VARCHAR(50),
    reference_id CHAR(36),
    notes TEXT,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('suppliers', 'idx_suppliers_org', '(org_id)');
CALL adpilot_create_index_if_missing('suppliers', 'idx_suppliers_status', '(status)');
CALL adpilot_create_index_if_missing('purchase_orders', 'idx_purchase_orders_store', '(store_id)');
CALL adpilot_create_index_if_missing('purchase_orders', 'idx_purchase_orders_supplier', '(supplier_id)');
CALL adpilot_create_index_if_missing('purchase_orders', 'idx_purchase_orders_status', '(status)');
CALL adpilot_create_index_if_missing('purchase_orders', 'idx_purchase_orders_date', '(order_date)');
CALL adpilot_create_index_if_missing('purchase_order_items', 'idx_purchase_order_items_po', '(purchase_order_id)');
CALL adpilot_create_index_if_missing('purchase_order_items', 'idx_purchase_order_items_sku', '(sku)');
CALL adpilot_create_index_if_missing('warehouse_locations', 'idx_warehouse_locations_org', '(org_id)');
CALL adpilot_create_index_if_missing('warehouse_inventory', 'idx_warehouse_inventory_location', '(warehouse_location_id)');
CALL adpilot_create_index_if_missing('warehouse_inventory', 'idx_warehouse_inventory_sku', '(sku)');
CALL adpilot_create_index_if_missing('replenishment_requests', 'idx_replenishment_requests_store', '(store_id)');
CALL adpilot_create_index_if_missing('replenishment_requests', 'idx_replenishment_requests_sku', '(sku)');
CALL adpilot_create_index_if_missing('replenishment_requests', 'idx_replenishment_requests_status', '(status)');
CALL adpilot_create_index_if_missing('inventory_movements', 'idx_inventory_movements_location', '(warehouse_location_id)');
CALL adpilot_create_index_if_missing('inventory_movements', 'idx_inventory_movements_sku', '(sku)');
CALL adpilot_create_index_if_missing('inventory_movements', 'idx_inventory_movements_type', '(movement_type)');
-- Shipments
CREATE TABLE IF NOT EXISTS shipments (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    shipment_id VARCHAR(255),
    shipment_type VARCHAR(50),
    status VARCHAR(30) DEFAULT 'pending',
    carrier VARCHAR(100),
    tracking_number VARCHAR(255),
    ship_from_address TEXT,
    ship_to_address TEXT,
    ship_date DATE,
    estimated_delivery_date DATE,
    actual_delivery_date DATE,
    total_weight DECIMAL(12,4),
    weight_unit VARCHAR(10),
    total_items INT DEFAULT 0,
    shipping_cost DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    notes TEXT,
    fba_shipment_id VARCHAR(100),
    amazon_shipment_status VARCHAR(50),
    destination_fc_code VARCHAR(50),
    reporting_currency CHAR(3),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Shipment Items
CREATE TABLE IF NOT EXISTS shipment_items (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    order_id VARCHAR(255),
    sku VARCHAR(100),
    asin VARCHAR(20),
    product_name VARCHAR(500),
    quantity INT DEFAULT 0,
    weight DECIMAL(12,4),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Invoices
CREATE TABLE IF NOT EXISTS invoices (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    invoice_number VARCHAR(100) NOT NULL,
    invoice_type VARCHAR(50),
    status VARCHAR(30) DEFAULT 'draft',
    issue_date DATE,
    due_date DATE,
    paid_date DATE,
    subtotal DECIMAL(18,4) DEFAULT 0,
    tax_amount DECIMAL(18,4) DEFAULT 0,
    total_amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    customer_name VARCHAR(255),
    customer_email VARCHAR(255),
    billing_address TEXT,
    notes TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Invoice Items
CREATE TABLE IF NOT EXISTS invoice_items (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    invoice_id CHAR(36) NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description VARCHAR(500),
    sku VARCHAR(100),
    quantity INT DEFAULT 0,
    unit_price DECIMAL(18,4) DEFAULT 0,
    total_price DECIMAL(18,4) DEFAULT 0,
    tax_rate DECIMAL(5,4) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Payments
CREATE TABLE IF NOT EXISTS payments (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    invoice_id CHAR(36) REFERENCES invoices(id),
    payment_number VARCHAR(100),
    payment_method VARCHAR(50),
    payment_date DATE,
    amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    status VARCHAR(30) DEFAULT 'pending',
    reference_number VARCHAR(255),
    notes TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Cost Allocations
CREATE TABLE IF NOT EXISTS cost_allocations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    cost_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id CHAR(36),
    amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    allocation_date DATE,
    description TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Financial Summaries
CREATE TABLE IF NOT EXISTS financial_summaries (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    summary_type VARCHAR(50) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_revenue DECIMAL(18,4) DEFAULT 0,
    total_cost DECIMAL(18,4) DEFAULT 0,
    total_fees DECIMAL(18,4) DEFAULT 0,
    gross_profit DECIMAL(18,4) DEFAULT 0,
    net_profit DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    details JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('shipments', 'idx_shipments_store', '(store_id)');
CALL adpilot_create_index_if_missing('shipments', 'idx_shipments_status', '(status)');
CALL adpilot_create_index_if_missing('shipments', 'idx_shipments_tracking', '(tracking_number)');
CALL adpilot_create_index_if_missing('shipments', 'idx_shipments_ship_date', '(ship_date)');
CALL adpilot_create_index_if_missing('shipment_items', 'idx_shipment_items_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('shipment_items', 'idx_shipment_items_sku', '(sku)');
CALL adpilot_create_index_if_missing('invoices', 'idx_invoices_store', '(store_id)');
CALL adpilot_create_index_if_missing('invoices', 'idx_invoices_status', '(status)');
CALL adpilot_create_index_if_missing('invoices', 'idx_invoices_issue_date', '(issue_date)');
CALL adpilot_create_index_if_missing('invoices', 'idx_invoices_due_date', '(due_date)');
CALL adpilot_create_index_if_missing('invoice_items', 'idx_invoice_items_invoice', '(invoice_id)');
CALL adpilot_create_index_if_missing('payments', 'idx_payments_store', '(store_id)');
CALL adpilot_create_index_if_missing('payments', 'idx_payments_invoice', '(invoice_id)');
CALL adpilot_create_index_if_missing('payments', 'idx_payments_status', '(status)');
CALL adpilot_create_index_if_missing('payments', 'idx_payments_date', '(payment_date)');
CALL adpilot_create_index_if_missing('cost_allocations', 'idx_cost_allocations_store', '(store_id)');
CALL adpilot_create_index_if_missing('cost_allocations', 'idx_cost_allocations_type', '(cost_type)');
CALL adpilot_create_index_if_missing('cost_allocations', 'idx_cost_allocations_date', '(allocation_date)');
CALL adpilot_create_index_if_missing('financial_summaries', 'idx_financial_summaries_store', '(store_id)');
CALL adpilot_create_index_if_missing('financial_summaries', 'idx_financial_summaries_type', '(summary_type)');
CALL adpilot_create_index_if_missing('financial_summaries', 'idx_financial_summaries_period', '(period_start, period_end)');
-- Customer Reviews
CREATE TABLE IF NOT EXISTS customer_reviews (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    review_id VARCHAR(255),
    asin VARCHAR(20),
    sku VARCHAR(100),
    reviewer_name VARCHAR(255),
    rating INT,
    title VARCHAR(500),
    review_text TEXT,
    review_date DATETIME(3),
    verified_purchase TINYINT(1) DEFAULT FALSE,
    helpful_votes INT DEFAULT 0,
    sentiment VARCHAR(20),
    sentiment_score DECIMAL(5,4),
    status VARCHAR(30) DEFAULT 'active',
    response_text TEXT,
    responded_at DATETIME(3),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Review Alerts
CREATE TABLE IF NOT EXISTS review_alerts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    review_id CHAR(36) REFERENCES customer_reviews(id),
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) DEFAULT 'medium',
    message TEXT,
    status VARCHAR(30) DEFAULT 'open',
    assigned_to CHAR(36),
    resolved_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Templates
CREATE TABLE IF NOT EXISTS listing_templates (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    template_name VARCHAR(255) NOT NULL,
    marketplace_id VARCHAR(50),
    category VARCHAR(255),
    title_template TEXT,
    bullet_templates JSON DEFAULT (JSON_ARRAY()),
    description_template TEXT,
    search_terms TEXT,
    keywords JSON DEFAULT (JSON_ARRAY()),
    status VARCHAR(30) DEFAULT 'active',
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Quality Checks
CREATE TABLE IF NOT EXISTS listing_quality_checks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    overall_score DECIMAL(5,2),
    title_score DECIMAL(5,2),
    bullet_score DECIMAL(5,2),
    description_score DECIMAL(5,2),
    image_score DECIMAL(5,2),
    keyword_score DECIMAL(5,2),
    issues JSON DEFAULT (JSON_ARRAY()),
    recommendations JSON DEFAULT (JSON_ARRAY()),
    checked_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Listing Optimization Logs
CREATE TABLE IF NOT EXISTS listing_optimization_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    optimization_type VARCHAR(100),
    field_changed VARCHAR(100),
    old_value TEXT,
    new_value TEXT,
    source VARCHAR(50),
    score_before DECIMAL(5,2),
    score_after DECIMAL(5,2),
    status VARCHAR(30) DEFAULT 'applied',
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Review Response Templates
CREATE TABLE IF NOT EXISTS review_response_templates (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    template_name VARCHAR(255) NOT NULL,
    response_type VARCHAR(50),
    rating_min INT,
    rating_max INT,
    response_text TEXT NOT NULL,
    variables JSON DEFAULT (JSON_ARRAY()),
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_store', '(store_id)');
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_asin', '(asin)');
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_rating', '(rating)');
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_date', '(review_date)');
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_sentiment', '(sentiment)');
CALL adpilot_create_index_if_missing('customer_reviews', 'idx_customer_reviews_status', '(status)');
CALL adpilot_create_index_if_missing('review_alerts', 'idx_review_alerts_store', '(store_id)');
CALL adpilot_create_index_if_missing('review_alerts', 'idx_review_alerts_type', '(alert_type)');
CALL adpilot_create_index_if_missing('review_alerts', 'idx_review_alerts_status', '(status)');
CALL adpilot_create_index_if_missing('review_alerts', 'idx_review_alerts_severity', '(severity)');
CALL adpilot_create_index_if_missing('listing_templates', 'idx_listing_templates_store', '(store_id)');
CALL adpilot_create_index_if_missing('listing_templates', 'idx_listing_templates_marketplace', '(marketplace_id)');
CALL adpilot_create_index_if_missing('listing_quality_checks', 'idx_listing_quality_checks_store', '(store_id)');
CALL adpilot_create_index_if_missing('listing_quality_checks', 'idx_listing_quality_checks_asin', '(asin)');

-- =====================================================================
-- Listing Ops monitoring tables (task 2.6 consistency fix)
-- These three tables are mapped by entities in the `listingops` module
-- (BuyBoxAlertEntity, HijackerAlertEntity, RepricingRuleEntity) but were
-- absent from every legacy SQL source and from this consolidated migration.
-- Under `spring.jpa.hibernate.ddl-auto=validate`, the missing tables would
-- fail Hibernate schema validation at startup. Columns/types are taken
-- directly from the entity @Column definitions.
-- =====================================================================

-- Buy Box Alerts (listingops/entity/BuyBoxAlertEntity.java)
CREATE TABLE IF NOT EXISTS buy_box_alerts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    product_name VARCHAR(500),
    buy_box_seller VARCHAR(255),
    buy_box_price DECIMAL(18,4),
    is_own_buy_box TINYINT(1) DEFAULT 0,
    alert_type VARCHAR(50),
    status VARCHAR(30) DEFAULT 'open',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Hijacker Alerts (listingops/entity/HijackerAlertEntity.java)
CREATE TABLE IF NOT EXISTS hijacker_alerts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    product_name VARCHAR(500),
    hijacker_seller VARCHAR(255),
    hijacker_price DECIMAL(18,4),
    alert_type VARCHAR(50),
    status VARCHAR(30) DEFAULT 'open',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Repricing Rules (listingops/entity/RepricingRuleEntity.java)
CREATE TABLE IF NOT EXISTS repricing_rules (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    rule_name VARCHAR(255) NOT NULL,
    min_price DECIMAL(18,4),
    max_price DECIMAL(18,4),
    strategy VARCHAR(50),
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Listing Monitors (listingops/entity/ListingMonitorEntity.java)
-- Distinct from listing_quality_checks (review module). ListingMonitorEntity
-- previously mapped to listing_quality_checks but maps issues/recommendations
-- as TEXT, conflicting with the review module's JSON columns on the same table
-- and breaking Hibernate ddl-auto=validate. Retargeted to its own table here.
-- Column types match the entity @Column definitions exactly (issues/
-- recommendations as TEXT; *_score as DECIMAL(5,2); CHAR(36) ids).
CREATE TABLE IF NOT EXISTS listing_monitors (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    asin VARCHAR(20),
    sku VARCHAR(100),
    overall_score DECIMAL(5,2),
    title_score DECIMAL(5,2),
    bullet_score DECIMAL(5,2),
    description_score DECIMAL(5,2),
    image_score DECIMAL(5,2),
    keyword_score DECIMAL(5,2),
    issues TEXT,
    recommendations TEXT,
    checked_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('buy_box_alerts', 'idx_buy_box_alerts_store', '(store_id)');
CALL adpilot_create_index_if_missing('buy_box_alerts', 'idx_buy_box_alerts_status', '(status)');
CALL adpilot_create_index_if_missing('hijacker_alerts', 'idx_hijacker_alerts_store', '(store_id)');
CALL adpilot_create_index_if_missing('hijacker_alerts', 'idx_hijacker_alerts_status', '(status)');
CALL adpilot_create_index_if_missing('repricing_rules', 'idx_repricing_rules_store', '(store_id)');
CALL adpilot_create_index_if_missing('repricing_rules', 'idx_repricing_rules_status', '(status)');
CALL adpilot_create_index_if_missing('listing_monitors', 'idx_listing_monitors_store', '(store_id)');
CALL adpilot_create_index_if_missing('listing_monitors', 'idx_listing_monitors_asin', '(asin)');
CALL adpilot_create_index_if_missing('listing_monitors', 'idx_listing_monitors_score', '(overall_score)');
CALL adpilot_create_index_if_missing('listing_quality_checks', 'idx_listing_quality_checks_score', '(overall_score)');
CALL adpilot_create_index_if_missing('listing_optimization_logs', 'idx_listing_optimization_logs_store', '(store_id)');
CALL adpilot_create_index_if_missing('listing_optimization_logs', 'idx_listing_optimization_logs_asin', '(asin)');
CALL adpilot_create_index_if_missing('listing_optimization_logs', 'idx_listing_optimization_logs_type', '(optimization_type)');
CALL adpilot_create_index_if_missing('review_response_templates', 'idx_review_response_templates_org', '(org_id)');
CALL adpilot_create_index_if_missing('review_response_templates', 'idx_review_response_templates_type', '(response_type)');
-- Platform Connections
CREATE TABLE IF NOT EXISTS platform_connections (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    platform VARCHAR(50) NOT NULL,
    connection_name VARCHAR(255),
    status VARCHAR(30) DEFAULT 'disconnected',
    config JSON DEFAULT (JSON_OBJECT()),
    -- V5: associate a connection with the Amazon seller account that owns the
    -- credentials (one seller account may cover multiple marketplaces) (Req 8.1.1)
    seller_account_id VARCHAR(255) NULL,
    -- V8: Amazon Ads OAuth (Login with Amazon) store-connection flow.
    -- A connection bound through the LWA OAuth wizard records the Amazon region
    -- (na/eu/fe), the selected advertising profile, its marketplace, and the
    -- LWA refresh token encrypted at rest via CryptoUtil (AES-256-GCM). The
    -- refresh token is the long-lived credential used to mint short-lived access
    -- tokens for the Amazon Ads API; it is never logged or returned to clients.
    region VARCHAR(10) NULL,
    profile_id VARCHAR(64) NULL,
    marketplace_id VARCHAR(64) NULL,
    refresh_token_encrypted TEXT NULL,
    last_sync_at DATETIME(3),
    last_error TEXT,
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('platform_connections', 'idx_platform_connections_seller_account', '(seller_account_id)');
-- project-fix-and-cleanup: reconcile the V5 seller-account + V13 Amazon Ads OAuth
-- columns onto a pre-existing platform_connections table (stale-DB upgrade), so
-- the API-connections / OAuth wizard does not 500 on missing columns.
CALL adpilot_add_column_if_missing('platform_connections', 'seller_account_id', 'VARCHAR(255) NULL');
CALL adpilot_add_column_if_missing('platform_connections', 'region', 'VARCHAR(10) NULL');
CALL adpilot_add_column_if_missing('platform_connections', 'profile_id', 'VARCHAR(64) NULL');
CALL adpilot_add_column_if_missing('platform_connections', 'marketplace_id', 'VARCHAR(64) NULL');
CALL adpilot_add_column_if_missing('platform_connections', 'refresh_token_encrypted', 'TEXT NULL');

-- API Tokens (encrypted in application layer);
CREATE TABLE IF NOT EXISTS api_tokens (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    token_type VARCHAR(50),
    token_value TEXT,
    expires_at DATETIME(3),
    scopes JSON DEFAULT (JSON_ARRAY()),
    status VARCHAR(20) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- API Sync Jobs
CREATE TABLE IF NOT EXISTS api_sync_jobs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    job_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    status VARCHAR(30) DEFAULT 'pending',
    total_records INT DEFAULT 0,
    processed_records INT DEFAULT 0,
    failed_records INT DEFAULT 0,
    error_message TEXT,
    started_at DATETIME(3),
    completed_at DATETIME(3),
    created_by CHAR(36),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- V3: per (store, entity_type) sync watermark for incremental retrieval (Req 1.1.6-8)
CREATE TABLE IF NOT EXISTS sync_watermarks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    entity_type VARCHAR(50) NOT NULL,
    watermark_at DATETIME(3),
    cursor_token VARCHAR(512),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_watermark (store_id, entity_type)
);

-- V3: per-record data-quality errors captured during mapping/validation (Req 1.4)
CREATE TABLE IF NOT EXISTS sync_record_errors (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    job_id CHAR(36) NOT NULL REFERENCES api_sync_jobs(id),
    external_entity_id VARCHAR(255),
    field VARCHAR(100),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('sync_record_errors', 'idx_sync_record_errors_job', '(job_id)');

-- API Sync Logs
CREATE TABLE IF NOT EXISTS api_sync_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    job_id CHAR(36) NOT NULL REFERENCES api_sync_jobs(id),
    log_level VARCHAR(20) DEFAULT 'info',
    message TEXT,
    details JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- API Error Logs
CREATE TABLE IF NOT EXISTS api_error_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) REFERENCES platform_connections(id),
    job_id CHAR(36) REFERENCES api_sync_jobs(id),
    error_code VARCHAR(100),
    error_message TEXT,
    request_url TEXT,
    request_method VARCHAR(10),
    response_status INT,
    response_body TEXT,
    retry_count INT DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- External Entity Mappings
CREATE TABLE IF NOT EXISTS external_entity_mappings (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    platform VARCHAR(50) NOT NULL,
    internal_entity_type VARCHAR(50) NOT NULL,
    internal_entity_id CHAR(36) NOT NULL,
    external_entity_type VARCHAR(50),
    external_entity_id VARCHAR(255),
    external_data JSON DEFAULT (JSON_OBJECT()),
    last_synced_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(store_id, platform, internal_entity_type, internal_entity_id),
    CONSTRAINT uq_eem_external UNIQUE (store_id, platform, external_entity_type, external_entity_id)
);

-- Channel Products
CREATE TABLE IF NOT EXISTS channel_products (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    external_product_id VARCHAR(255),
    sku VARCHAR(100),
    title VARCHAR(500),
    price DECIMAL(18,4),
    currency VARCHAR(10),
    status VARCHAR(30),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    last_synced_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Channel Orders
CREATE TABLE IF NOT EXISTS channel_orders (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    external_order_id VARCHAR(255),
    order_status VARCHAR(50),
    total_amount DECIMAL(18,4),
    currency VARCHAR(10),
    order_date DATETIME(3),
    raw_data JSON DEFAULT (JSON_OBJECT()),
    last_synced_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Channel Inventory Sync
CREATE TABLE IF NOT EXISTS channel_inventory_sync (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    sku VARCHAR(100),
    external_product_id VARCHAR(255),
    quantity INT DEFAULT 0,
    sync_direction VARCHAR(20),
    last_synced_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Sync Schedules
CREATE TABLE IF NOT EXISTS sync_schedules (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    connection_id CHAR(36) NOT NULL REFERENCES platform_connections(id),
    job_type VARCHAR(100) NOT NULL,
    cron_expression VARCHAR(100),
    enabled TINYINT(1) DEFAULT 1,
    last_run_at DATETIME(3),
    next_run_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('platform_connections', 'idx_platform_connections_store', '(store_id)');
CALL adpilot_create_index_if_missing('platform_connections', 'idx_platform_connections_platform', '(platform)');
CALL adpilot_create_index_if_missing('platform_connections', 'idx_platform_connections_status', '(status)');
CALL adpilot_create_index_if_missing('api_tokens', 'idx_api_tokens_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('api_sync_jobs', 'idx_api_sync_jobs_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('api_sync_jobs', 'idx_api_sync_jobs_status', '(status)');
CALL adpilot_create_index_if_missing('api_sync_logs', 'idx_api_sync_logs_job', '(job_id)');
CALL adpilot_create_index_if_missing('api_error_logs', 'idx_api_error_logs_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('external_entity_mappings', 'idx_external_entity_mappings_store', '(store_id)');
CALL adpilot_create_index_if_missing('external_entity_mappings', 'idx_external_entity_mappings_platform', '(platform)');
CALL adpilot_create_index_if_missing('external_entity_mappings', 'idx_external_entity_mappings_store_platform_ext', '(store_id, platform, external_entity_type, external_entity_id)');
CALL adpilot_create_index_if_missing('channel_products', 'idx_channel_products_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('channel_orders', 'idx_channel_orders_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('channel_inventory_sync', 'idx_channel_inventory_sync_connection', '(connection_id)');
CALL adpilot_create_index_if_missing('sync_schedules', 'idx_sync_schedules_connection', '(connection_id)');
CREATE TABLE IF NOT EXISTS cash_flow (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    date DATE NOT NULL,
    category VARCHAR(100),
    description TEXT,
    inflow DECIMAL(18,4) DEFAULT 0,
    outflow DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS receivables (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    source_type VARCHAR(50),
    amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    due_date DATE,
    status VARCHAR(30) DEFAULT 'pending',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS payables (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    supplier_id CHAR(36),
    source_type VARCHAR(50),
    amount DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    due_date DATE,
    status VARCHAR(30) DEFAULT 'pending',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS customer_tickets (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    order_id VARCHAR(255),
    buyer_email VARCHAR(255),
    subject VARCHAR(500),
    description TEXT,
    category VARCHAR(100),
    priority VARCHAR(20) DEFAULT 'medium',
    status VARCHAR(30) DEFAULT 'open',
    assigned_to CHAR(36),
    -- platform-workspace-rbac: associate tickets with their store group for
    -- isolation (Req 9.2); AI provenance flag (Req 9.5) and AI classification.
    store_group_id CHAR(36) NULL REFERENCES store_groups(id),
    ai_drafted TINYINT(1) NOT NULL DEFAULT 0,
    ai_classification VARCHAR(100) NULL,
    resolved_at DATETIME(3),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- platform-workspace-rbac (Req 9.2, 18): ensure the store-group column exists on
-- a pre-existing `customer_tickets` table before its index, for clean re-import.
CALL adpilot_add_column_if_missing('customer_tickets', 'store_group_id', 'CHAR(36) NULL');
CALL adpilot_create_index_if_missing('customer_tickets', 'idx_customer_tickets_store_group', '(store_group_id)');

-- Account Platform Access (platform-workspace-rbac Req 12, 18)
-- Which of the four Nav_Blocks an account may enter. UNIQUE(user_id,
-- platform_family) keeps grants idempotent (Req 18.6). An account with no rows
-- after migration receives a defined default Platform_Access (Req 18.5).
CREATE TABLE IF NOT EXISTS account_platform_access (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform_family VARCHAR(20) NOT NULL
        CHECK (platform_family IN ('amazon', 'independent_site', 'logistics', 'finance')),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_account_platform (user_id, platform_family)
);

CALL adpilot_create_index_if_missing('account_platform_access', 'idx_account_platform_access_user', '(user_id)');

-- multistore-ai-ads-operations Req 5.1, 6.1: widen Platform_Access to include
-- the fifth Nav_Block family 'tiktok'. Idempotent — replaces the legacy inline
-- CHECK with a named constraint allowing the new value.
CALL adpilot_replace_check_constraint(
    'account_platform_access',
    'chk_apa_family',
    "(platform_family IN ('amazon', 'independent_site', 'logistics', 'finance', 'tiktok'))");

-- =====================================================================
-- Tables authored fresh in task 2.3 (missing from all legacy SQL sources)
-- =====================================================================

-- Login Logs (authentication audit trail)
-- No CREATE TABLE existed in any legacy SQL source; authored fresh per Req 4.1.
CREATE TABLE IF NOT EXISTS login_logs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36),
    org_id CHAR(36),
    username VARCHAR(255),
    email VARCHAR(255),
    ip_address VARCHAR(50),
    user_agent TEXT,
    login_status VARCHAR(20) NOT NULL DEFAULT 'success',
    failure_reason VARCHAR(255),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Purchase Requests (procurement intake; DISTINCT from purchase_orders)
-- No CREATE TABLE existed in any legacy SQL source; columns inferred from the
-- legacy seed in dml/010_seed_procurement_warehouse.sql. Authored fresh per Req 4.1/4.2.
CREATE TABLE IF NOT EXISTS purchase_requests (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    supplier_id CHAR(36) REFERENCES suppliers(id),
    product_id CHAR(36) REFERENCES products(id),
    sku VARCHAR(100),
    quantity INT NOT NULL DEFAULT 0,
    unit_cost DECIMAL(18,4) DEFAULT 0,
    total_cost DECIMAL(18,4) DEFAULT 0,
    currency VARCHAR(10),
    reason TEXT,
    expected_date DATE,
    status VARCHAR(30) DEFAULT 'pending',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('login_logs', 'idx_login_logs_user', '(user_id)');
CALL adpilot_create_index_if_missing('login_logs', 'idx_login_logs_org', '(org_id)');
CALL adpilot_create_index_if_missing('login_logs', 'idx_login_logs_status', '(login_status)');
CALL adpilot_create_index_if_missing('login_logs', 'idx_login_logs_created', '(created_at)');
CALL adpilot_create_index_if_missing('purchase_requests', 'idx_purchase_requests_store', '(store_id)');
CALL adpilot_create_index_if_missing('purchase_requests', 'idx_purchase_requests_supplier', '(supplier_id)');
CALL adpilot_create_index_if_missing('purchase_requests', 'idx_purchase_requests_product', '(product_id)');
CALL adpilot_create_index_if_missing('purchase_requests', 'idx_purchase_requests_status', '(status)');

-- =====================================================================
-- V6: Exchange rates by currency pair and effective date (Req 9.2)
-- =====================================================================
CREATE TABLE IF NOT EXISTS exchange_rates (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    base_currency VARCHAR(10) NOT NULL,
    quote_currency VARCHAR(10) NOT NULL,
    rate DECIMAL(18,8) NOT NULL,
    effective_date DATE NOT NULL,
    source VARCHAR(50),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_rate (base_currency, quote_currency, effective_date)
);

-- =====================================================================
-- V8: Unified alert center (Req 10.1).
-- Dedups to one OPEN alert per (store, type, subject) via a generated column
-- that is populated only while the alert is open and NULL once resolved
-- (NULLs are not compared by MySQL unique indexes), so resolved history is
-- retained without limit.
-- =====================================================================
CREATE TABLE IF NOT EXISTS alerts (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL,
    alert_type VARCHAR(50) NOT NULL,        -- stockout|acos|buybox|negative_review
    subject_id VARCHAR(255),                -- product/campaign identifier
    severity VARCHAR(20) DEFAULT 'warning',
    status VARCHAR(20) DEFAULT 'open',      -- open|resolved
    message TEXT,
    feishu_pushed TINYINT(1) DEFAULT 0,     -- 1 once successfully pushed to Feishu
    feishu_error TEXT,                      -- Req 10.1.9: reason a push failed (alert retained)
    first_seen_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3),
    open_dedup_key VARCHAR(512) GENERATED ALWAYS AS (
        CASE WHEN status = 'open'
             THEN CONCAT(store_id, ':', alert_type, ':', COALESCE(subject_id, ''))
             ELSE NULL
        END
    ) STORED,
    UNIQUE KEY uk_open_alert (open_dedup_key),
    KEY idx_alerts_store_status (store_id, status),
    KEY idx_alerts_type (alert_type)
);

-- =====================================================================
-- V11: Automation rules with bid bounds (Req 13.2). Distinct from
-- automation_policies (org/store-wide risk guardrails). Each row is a single
-- evaluable rule run by the AutomationRunner on each scheduled pass.
-- =====================================================================
CREATE TABLE IF NOT EXISTS automation_rules (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    rule_type VARCHAR(50) NOT NULL,         -- bid_adjustment|negative_keyword
    enabled TINYINT(1) DEFAULT 1,
    condition_json JSON,
    min_bid DECIMAL(18,4),
    max_bid DECIMAL(18,4),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('automation_rules', 'idx_automation_rules_store', '(store_id)');
CALL adpilot_create_index_if_missing('automation_rules', 'idx_automation_rules_enabled', '(rule_type, enabled)');

-- =====================================================================
-- Section 2: AI settings table + default row (merged from V3)
-- =====================================================================
-- =====================================================================
-- V3__ai_settings.sql
-- Configuration table for the pluggable, OpenAI-compatible AI integration.
-- A single active row (per org) drives the AiClient at runtime, so the
-- provider/base-url/model/key can be changed without restarting the app.
-- =====================================================================

CREATE TABLE IF NOT EXISTS ai_settings (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36),
    provider VARCHAR(50) NOT NULL DEFAULT 'openai',     -- openai|azure|deepseek|moonshot|qwen|ollama|custom
    base_url VARCHAR(500) NOT NULL DEFAULT 'https://api.openai.com/v1',
    api_key VARCHAR(500),                                -- stored as-is; masked on read
    model VARCHAR(200) NOT NULL DEFAULT 'gpt-4o-mini',
    temperature DECIMAL(4,2) NOT NULL DEFAULT 0.70,
    max_tokens INT NOT NULL DEFAULT 1024,
    enabled TINYINT(1) NOT NULL DEFAULT 0,               -- off until a key is configured
    extra_headers JSON,                                  -- optional provider-specific headers
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Seed a single default (disabled) row for the demo org so the settings
-- page always has something to edit.
INSERT IGNORE INTO ai_settings (id, org_id, provider, base_url, model, enabled) VALUES
('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000001', 'openai', 'https://api.openai.com/v1', 'gpt-4o-mini', 0);

-- =====================================================================
-- Section 3: Seed / reference data (merged from V2)
-- =====================================================================
-- =====================================================================
-- V2__seed_data.sql
-- Consolidated MySQL 8.0 seed/DML for AdPilot (ASCII-only to avoid any
-- encoding issues). Schema (DDL) lives in V1; this file is DML only.
-- Every statement uses INSERT IGNORE so re-running is safe.
-- =====================================================================

-- Organization
INSERT IGNORE INTO organizations (id, name, plan) VALUES
('00000000-0000-0000-0000-000000000001', 'Demo Commerce Team', 'growth');

-- Departments
INSERT IGNORE INTO departments (id, org_id, name, code) VALUES
('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 'Head Office', 'HO'),
('00000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000001', 'Operations', 'OPS'),
('00000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000001', 'Advertising', 'ADS'),
('00000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000001', 'Finance', 'FIN'),
('00000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000001', 'Logistics', 'LOG'),
('00000000-0000-0000-0000-000000000015', '00000000-0000-0000-0000-000000000001', 'Warehouse', 'WH'),
('00000000-0000-0000-0000-000000000016', '00000000-0000-0000-0000-000000000001', 'Procurement', 'PROC'),
('00000000-0000-0000-0000-000000000017', '00000000-0000-0000-0000-000000000001', 'Customer Service', 'CS'),
('00000000-0000-0000-0000-000000000018', '00000000-0000-0000-0000-000000000001', 'Product', 'PROD'),
('00000000-0000-0000-0000-000000000019', '00000000-0000-0000-0000-000000000001', 'Design', 'DESIGN'),
('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000001', 'Tech', 'TECH');

-- Roles
INSERT IGNORE INTO roles (id, org_id, name, code, description, is_system) VALUES
('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000001', 'Super Admin', 'super_admin', 'Full system access', 1),
('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000001', 'General Manager', 'general_manager', 'View all data, approve high-risk actions', 1),
('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000001', 'Operations Manager', 'operations_manager', 'Manage operations, advertising, products', 1),
('00000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000001', 'Operation Specialist', 'operation_specialist', 'Products, listing, orders', 1),
('00000000-0000-0000-0000-000000000205', '00000000-0000-0000-0000-000000000001', 'Advertising Specialist', 'advertising_specialist', 'Ad campaign management', 1),
('00000000-0000-0000-0000-000000000206', '00000000-0000-0000-0000-000000000001', 'Finance Specialist', 'finance_specialist', 'Finance, settlement, cost', 1),
('00000000-0000-0000-0000-000000000207', '00000000-0000-0000-0000-000000000001', 'Logistics Specialist', 'logistics_specialist', 'FBA, logistics, inbound/outbound', 1),
('00000000-0000-0000-0000-000000000208', '00000000-0000-0000-0000-000000000001', 'Warehouse Specialist', 'warehouse_specialist', 'Warehouse, inventory, QC', 1),
('00000000-0000-0000-0000-000000000209', '00000000-0000-0000-0000-000000000001', 'Procurement Specialist', 'procurement_specialist', 'Suppliers, purchase orders', 1),
('00000000-0000-0000-0000-000000000210', '00000000-0000-0000-0000-000000000001', 'Customer Service', 'customer_service', 'Tickets, reviews, feedback', 1),
('00000000-0000-0000-0000-000000000211', '00000000-0000-0000-0000-000000000001', 'Product Manager', 'product_manager', 'Products, listing AI, upload', 1),
('00000000-0000-0000-0000-000000000212', '00000000-0000-0000-0000-000000000001', 'Designer', 'designer', 'View products, listing content', 1),
('00000000-0000-0000-0000-000000000213', '00000000-0000-0000-0000-000000000001', 'Viewer', 'viewer', 'Read-only access', 1);

-- Permissions
INSERT IGNORE INTO permissions (id, code, name, module, action) VALUES
('00000000-0000-0000-0000-000000002001', 'dashboard:view', 'View Dashboard', 'dashboard', 'view'),
('00000000-0000-0000-0000-000000002002', 'dashboard:export', 'Export Dashboard', 'dashboard', 'export'),
('00000000-0000-0000-0000-000000002011', 'order:view', 'View Orders', 'order', 'view'),
('00000000-0000-0000-0000-000000002012', 'order:create', 'Create Order', 'order', 'create'),
('00000000-0000-0000-0000-000000002013', 'order:update', 'Update Order', 'order', 'update'),
('00000000-0000-0000-0000-000000002014', 'order:import', 'Import Orders', 'order', 'import'),
('00000000-0000-0000-0000-000000002015', 'order:export', 'Export Orders', 'order', 'export'),
('00000000-0000-0000-0000-000000002021', 'advertising:view', 'View Advertising', 'advertising', 'view'),
('00000000-0000-0000-0000-000000002022', 'advertising:manage', 'Manage Advertising', 'advertising', 'manage'),
('00000000-0000-0000-0000-000000002023', 'advertising:approve', 'Approve Advertising', 'advertising', 'approve'),
('00000000-0000-0000-0000-000000002024', 'advertising:execute', 'Execute Advertising (platform)', 'advertising', 'execute'),
-- platform-workspace-rbac (Req 14.2): distinct advertising-operation permissions per platform
-- family so an account may hold Amazon advertising operations without independent-site
-- advertising operations and vice versa. The independent-site permission gates the Google Ads
-- manual write endpoints (Req 7.5).
('00000000-0000-0000-0000-000000002027', 'advertising:amazon:operate', 'Operate Amazon Advertising', 'advertising', 'amazon:operate'),
('00000000-0000-0000-0000-000000002028', 'advertising:independent_site:operate', 'Operate Independent-Site Advertising', 'advertising', 'independent_site:operate'),
('00000000-0000-0000-0000-000000002025', 'operation:view', 'View Operations (audit)', 'operation', 'view'),
('00000000-0000-0000-0000-000000002026', 'hosting:manage', 'Manage AI Hosting', 'hosting', 'manage'),
('00000000-0000-0000-0000-000000002031', 'product:view', 'View Products', 'product', 'view'),
('00000000-0000-0000-0000-000000002032', 'product:create', 'Create Product', 'product', 'create'),
('00000000-0000-0000-0000-000000002033', 'product:update', 'Update Product', 'product', 'update'),
('00000000-0000-0000-0000-000000002034', 'product:delete', 'Delete Product', 'product', 'delete'),
('00000000-0000-0000-0000-000000002041', 'keyword:view', 'View Keywords', 'keyword', 'view'),
('00000000-0000-0000-0000-000000002042', 'keyword:manage', 'Manage Keywords', 'keyword', 'manage'),
('00000000-0000-0000-0000-000000002043', 'keyword:apply', 'Apply Keywords', 'keyword', 'apply'),
('00000000-0000-0000-0000-000000002051', 'finance:view', 'View Finance', 'finance', 'view'),
('00000000-0000-0000-0000-000000002052', 'finance:manage', 'Manage Finance', 'finance', 'manage'),
('00000000-0000-0000-0000-000000002053', 'finance:approve', 'Approve Finance', 'finance', 'approve'),
('00000000-0000-0000-0000-000000002061', 'warehouse:view', 'View Warehouse', 'warehouse', 'view'),
('00000000-0000-0000-0000-000000002062', 'warehouse:manage', 'Manage Warehouse', 'warehouse', 'manage'),
('00000000-0000-0000-0000-000000002071', 'procurement:view', 'View Procurement', 'procurement', 'view'),
('00000000-0000-0000-0000-000000002072', 'procurement:manage', 'Manage Procurement', 'procurement', 'manage'),
('00000000-0000-0000-0000-000000002073', 'procurement:approve', 'Approve Procurement', 'procurement', 'approve'),
('00000000-0000-0000-0000-000000002081', 'customer:view', 'View Customer Service', 'customer', 'view'),
('00000000-0000-0000-0000-000000002082', 'customer:manage', 'Manage Customer Service', 'customer', 'manage'),
('00000000-0000-0000-0000-000000002091', 'review:view', 'View Reviews', 'review', 'view'),
('00000000-0000-0000-0000-000000002092', 'review:manage', 'Manage Reviews', 'review', 'manage'),
('00000000-0000-0000-0000-000000002101', 'approval:view', 'View Approvals', 'approval', 'view'),
('00000000-0000-0000-0000-000000002102', 'approval:approve_low', 'Approve Low Risk', 'approval', 'approve_low'),
('00000000-0000-0000-0000-000000002103', 'approval:approve_medium', 'Approve Medium Risk', 'approval', 'approve_medium'),
('00000000-0000-0000-0000-000000002104', 'approval:approve_high', 'Approve High Risk', 'approval', 'approve_high'),
('00000000-0000-0000-0000-000000002111', 'user:view', 'View Users', 'user', 'view'),
('00000000-0000-0000-0000-000000002112', 'user:manage', 'Manage Users', 'user', 'manage'),
('00000000-0000-0000-0000-000000002121', 'role:view', 'View Roles', 'role', 'view'),
('00000000-0000-0000-0000-000000002122', 'role:manage', 'Manage Roles', 'role', 'manage'),
('00000000-0000-0000-0000-000000002131', 'department:view', 'View Departments', 'department', 'view'),
('00000000-0000-0000-0000-000000002132', 'department:manage', 'Manage Departments', 'department', 'manage'),
('00000000-0000-0000-0000-000000002141', 'import:view', 'View Imports', 'import', 'view'),
('00000000-0000-0000-0000-000000002142', 'import:manage', 'Manage Imports', 'import', 'manage'),
('00000000-0000-0000-0000-000000002151', 'report:view', 'View Reports', 'report', 'view'),
('00000000-0000-0000-0000-000000002152', 'report:export', 'Export Reports', 'report', 'export'),
('00000000-0000-0000-0000-000000002161', 'automation:view', 'View Automation', 'automation', 'view'),
('00000000-0000-0000-0000-000000002162', 'automation:manage', 'Manage Automation', 'automation', 'manage'),
('00000000-0000-0000-0000-000000002171', 'feishu:view', 'View Feishu', 'feishu', 'view'),
('00000000-0000-0000-0000-000000002172', 'feishu:manage', 'Manage Feishu', 'feishu', 'manage'),
('00000000-0000-0000-0000-000000002181', 'audit:view', 'View Audit', 'audit', 'view'),
('00000000-0000-0000-0000-000000002182', 'audit:manage', 'Manage Audit', 'audit', 'manage'),
('00000000-0000-0000-0000-000000002191', 'store:view', 'View Stores', 'store', 'view'),
('00000000-0000-0000-0000-000000002192', 'store:manage', 'Manage Stores', 'store', 'manage');

-- Super Admin gets all permissions
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000201', id FROM permissions;

-- Viewer gets all view permissions
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000213', id FROM permissions WHERE action = 'view';

-- Operations roles own both advertising and product execution. Keep these
-- assignments idempotent so re-running schema.sql also repairs existing demo DBs.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000203', id
FROM permissions
WHERE module IN (
    'dashboard', 'order', 'advertising', 'operation', 'hosting', 'product',
    'keyword', 'approval', 'import', 'report', 'automation', 'audit', 'warehouse', 'store'
);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT role_id, permission_id
FROM (
    SELECT r.id AS role_id, p.id AS permission_id
    FROM roles r
    JOIN permissions p ON p.code IN (
        'dashboard:view',
        'order:view', 'order:update', 'order:import', 'order:export',
        'advertising:view', 'advertising:manage', 'advertising:execute',
        'advertising:amazon:operate', 'advertising:independent_site:operate',
        'operation:view', 'hosting:manage',
        'product:view', 'product:create', 'product:update',
        'keyword:view', 'keyword:manage', 'keyword:apply',
        'approval:view',
        'import:view', 'import:manage',
        'report:view', 'report:export',
        'automation:view', 'automation:manage',
        'audit:view',
        'warehouse:view', 'warehouse:manage',
        'store:view'
    )
    WHERE r.code IN ('operation_specialist', 'advertising_specialist')
) operation_permissions;

-- Feishu integration management (飞书机器人 / Account_Area).
-- The nav item and route gate on `feishu:view`, but every mutating endpoint
-- (connect / update / test-message / chat-bindings / notification-rules)
-- requires `feishu:manage`. Previously only Super Admin held `feishu:manage`,
-- so a manager who could SEE the page could not connect a bot or send a test
-- message (every action returned 403) — the page was effectively unusable.
-- Grant BOTH permissions to the management roles so the page is usable end to
-- end. Idempotent so re-running schema.sql also repairs existing demo DBs.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('feishu:view', 'feishu:manage')
WHERE r.code IN ('general_manager', 'operations_manager');

-- Users (password: Adpilot@123456, BCrypt hash)
INSERT IGNORE INTO users (id, org_id, email, name, password_hash, status) VALUES
('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 'admin@adpilot.local', 'System Admin', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000001', 'boss@adpilot.local', 'GM Zhang', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000001', 'ops.manager@adpilot.local', 'Ops Manager Li', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000304', '00000000-0000-0000-0000-000000000001', 'ops01@adpilot.local', 'Ops Wang', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000305', '00000000-0000-0000-0000-000000000001', 'ops02@adpilot.local', 'Ops Liu', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000306', '00000000-0000-0000-0000-000000000001', 'ads01@adpilot.local', 'Ads Chen', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000307', '00000000-0000-0000-0000-000000000001', 'ads02@adpilot.local', 'Ads Zhao', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000308', '00000000-0000-0000-0000-000000000001', 'finance01@adpilot.local', 'Finance Qian', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000309', '00000000-0000-0000-0000-000000000001', 'finance02@adpilot.local', 'Finance Sun', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000310', '00000000-0000-0000-0000-000000000001', 'logistics01@adpilot.local', 'Logistics Zhou', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000311', '00000000-0000-0000-0000-000000000001', 'logistics02@adpilot.local', 'Logistics Wu', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000312', '00000000-0000-0000-0000-000000000001', 'warehouse01@adpilot.local', 'Warehouse Zheng', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000313', '00000000-0000-0000-0000-000000000001', 'warehouse02@adpilot.local', 'Warehouse Feng', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000314', '00000000-0000-0000-0000-000000000001', 'procurement01@adpilot.local', 'Procurement Han', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000315', '00000000-0000-0000-0000-000000000001', 'procurement02@adpilot.local', 'Procurement Yang', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000316', '00000000-0000-0000-0000-000000000001', 'cs01@adpilot.local', 'CS Zhu', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000317', '00000000-0000-0000-0000-000000000001', 'cs02@adpilot.local', 'CS Qin', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000318', '00000000-0000-0000-0000-000000000001', 'product01@adpilot.local', 'Product You', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000319', '00000000-0000-0000-0000-000000000001', 'designer01@adpilot.local', 'Designer Xu', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active'),
('00000000-0000-0000-0000-000000000320', '00000000-0000-0000-0000-000000000001', 'viewer01@adpilot.local', 'Viewer He', '$2b$10$EOxlyP7T35q/dbwvGBwYyuHc3/cXJprN24Qrn1FQJBSjDAr1.9ipu', 'active');

-- User-Role assignments
INSERT IGNORE INTO user_roles (user_id, role_id) VALUES
('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000201'),
('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000202'),
('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000203'),
('00000000-0000-0000-0000-000000000304', '00000000-0000-0000-0000-000000000204'),
('00000000-0000-0000-0000-000000000305', '00000000-0000-0000-0000-000000000204'),
('00000000-0000-0000-0000-000000000306', '00000000-0000-0000-0000-000000000205'),
('00000000-0000-0000-0000-000000000307', '00000000-0000-0000-0000-000000000205'),
('00000000-0000-0000-0000-000000000308', '00000000-0000-0000-0000-000000000206'),
('00000000-0000-0000-0000-000000000309', '00000000-0000-0000-0000-000000000206'),
('00000000-0000-0000-0000-000000000310', '00000000-0000-0000-0000-000000000207'),
('00000000-0000-0000-0000-000000000311', '00000000-0000-0000-0000-000000000207'),
('00000000-0000-0000-0000-000000000312', '00000000-0000-0000-0000-000000000208'),
('00000000-0000-0000-0000-000000000313', '00000000-0000-0000-0000-000000000208'),
('00000000-0000-0000-0000-000000000314', '00000000-0000-0000-0000-000000000209'),
('00000000-0000-0000-0000-000000000315', '00000000-0000-0000-0000-000000000209'),
('00000000-0000-0000-0000-000000000316', '00000000-0000-0000-0000-000000000210'),
('00000000-0000-0000-0000-000000000317', '00000000-0000-0000-0000-000000000210'),
('00000000-0000-0000-0000-000000000318', '00000000-0000-0000-0000-000000000211'),
('00000000-0000-0000-0000-000000000319', '00000000-0000-0000-0000-000000000212'),
('00000000-0000-0000-0000-000000000320', '00000000-0000-0000-0000-000000000213');

-- User-Department assignments
INSERT IGNORE INTO user_departments (user_id, department_id, is_primary) VALUES
('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000020', 1),
('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000010', 1),
('00000000-0000-0000-0000-000000000303', '00000000-0000-0000-0000-000000000011', 1),
('00000000-0000-0000-0000-000000000304', '00000000-0000-0000-0000-000000000011', 1),
('00000000-0000-0000-0000-000000000305', '00000000-0000-0000-0000-000000000011', 1),
('00000000-0000-0000-0000-000000000306', '00000000-0000-0000-0000-000000000012', 1),
('00000000-0000-0000-0000-000000000307', '00000000-0000-0000-0000-000000000012', 1),
('00000000-0000-0000-0000-000000000308', '00000000-0000-0000-0000-000000000013', 1),
('00000000-0000-0000-0000-000000000309', '00000000-0000-0000-0000-000000000013', 1),
('00000000-0000-0000-0000-000000000310', '00000000-0000-0000-0000-000000000014', 1),
('00000000-0000-0000-0000-000000000311', '00000000-0000-0000-0000-000000000014', 1),
('00000000-0000-0000-0000-000000000312', '00000000-0000-0000-0000-000000000015', 1),
('00000000-0000-0000-0000-000000000313', '00000000-0000-0000-0000-000000000015', 1),
('00000000-0000-0000-0000-000000000314', '00000000-0000-0000-0000-000000000016', 1),
('00000000-0000-0000-0000-000000000315', '00000000-0000-0000-0000-000000000016', 1),
('00000000-0000-0000-0000-000000000316', '00000000-0000-0000-0000-000000000017', 1),
('00000000-0000-0000-0000-000000000317', '00000000-0000-0000-0000-000000000017', 1),
('00000000-0000-0000-0000-000000000318', '00000000-0000-0000-0000-000000000018', 1),
('00000000-0000-0000-0000-000000000319', '00000000-0000-0000-0000-000000000019', 1),
('00000000-0000-0000-0000-000000000320', '00000000-0000-0000-0000-000000000010', 1);

-- Data Scopes (role_id, scope_type) - scope_type mapped to entity values
INSERT IGNORE INTO data_scopes (id, role_id, scope_type) VALUES
('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000201', 'all_company'),
('00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000202', 'all_company'),
('00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000203', 'assigned_store'),
('00000000-0000-0000-0000-000000000404', '00000000-0000-0000-0000-000000000204', 'department'),
('00000000-0000-0000-0000-000000000405', '00000000-0000-0000-0000-000000000205', 'assigned_store'),
('00000000-0000-0000-0000-000000000406', '00000000-0000-0000-0000-000000000206', 'all_company'),
('00000000-0000-0000-0000-000000000407', '00000000-0000-0000-0000-000000000207', 'department'),
('00000000-0000-0000-0000-000000000408', '00000000-0000-0000-0000-000000000208', 'department'),
('00000000-0000-0000-0000-000000000409', '00000000-0000-0000-0000-000000000209', 'department'),
('00000000-0000-0000-0000-000000000410', '00000000-0000-0000-0000-000000000210', 'department'),
('00000000-0000-0000-0000-000000000411', '00000000-0000-0000-0000-000000000211', 'department'),
('00000000-0000-0000-0000-000000000412', '00000000-0000-0000-0000-000000000212', 'department'),
('00000000-0000-0000-0000-000000000413', '00000000-0000-0000-0000-000000000213', 'department');

-- Marketplaces
INSERT IGNORE INTO marketplaces (id, code, name, currency, flag) VALUES
('00000000-0000-0000-0000-000000000201', 'US', 'Amazon.com (US)', 'USD', 'US'),
('00000000-0000-0000-0000-000000000202', 'UK', 'Amazon.co.uk', 'GBP', 'UK'),
('00000000-0000-0000-0000-000000000203', 'DE', 'Amazon.de', 'EUR', 'DE'),
('00000000-0000-0000-0000-000000000204', 'CA', 'Amazon.ca', 'CAD', 'CA');

-- V12: VAT applicability for the reference marketplaces. EU/UK are subject to
-- VAT; US/CA are not (sales tax handled separately). (Req 9.2.3)
UPDATE marketplaces SET vat_applicable = TRUE,  vat_rate = 0.2000 WHERE code = 'UK';
UPDATE marketplaces SET vat_applicable = TRUE,  vat_rate = 0.1900 WHERE code = 'DE';
UPDATE marketplaces SET vat_applicable = FALSE, vat_rate = NULL   WHERE code IN ('US', 'CA');

-- Store
INSERT IGNORE INTO stores (id, org_id, name, marketplace_id, seller_id, status) VALUES
('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 'Demo Amazon US Store', '00000000-0000-0000-0000-000000000201', 'DEMO-SELLER-001', 'connected');

-- Products
INSERT IGNORE INTO products (id, store_id, sku, asin, name, price, cost, gross_margin, inventory, target_acos, break_even_acos, category, brand, status) VALUES
('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', 'WALKPAD-001', 'B0DEMO001', 'Walking Pad', 299.99, 85.00, 0.7166, 3200, 0.22, 0.7166, 'Sports & Fitness', 'FitWalk', 'active'),
('00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000301', 'EBIKE-001', 'B0DEMO002', 'Electric Dirt Bike', 1499.99, 520.00, 0.6533, 180, 0.15, 0.6533, 'Outdoor Recreation', 'VoltRide', 'active'),
('00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000301', 'HITCH-001', 'B0DEMO003', 'Trailer Hitch', 89.99, 22.00, 0.7555, 5600, 0.18, 0.7555, 'Automotive', 'HaulMaster', 'active'),
('00000000-0000-0000-0000-000000000404', '00000000-0000-0000-0000-000000000301', 'BEDFR-001', 'B0DEMO004', 'Bed Frame', 249.99, 72.00, 0.7120, 890, 0.20, 0.7120, 'Furniture', 'DreamBase', 'active'),
('00000000-0000-0000-0000-000000000405', '00000000-0000-0000-0000-000000000301', 'VIBPLT-001', 'B0DEMO005', 'Vibration Plate', 149.99, 38.00, 0.7466, 15, 0.25, 0.7466, 'Fitness Equipment', 'VibeFit', 'active');



-- =====================================================================
-- ===  V2__ai_advertising_module.sql  ===
-- =====================================================================

-- =====================================================================
-- V2__ai_advertising_module.sql  (ADDITIVE)
-- AI advertising module schema �?Ad Portfolios (Req 20) + AI Hosting (Req 21).
-- This migration is purely additive; V1__init_schema.sql is never edited.
-- MySQL 8.0 conventions (mirroring V1):
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--     (updated_at also ON UPDATE CURRENT_TIMESTAMP(3))
--   * money -> DECIMAL(18,4); rates/percentages -> DECIMAL(10,4)
--   * status enums -> VARCHAR
-- =====================================================================

-- ---------------------------------------------------------------------
-- AI Hosting columns on existing campaigns table (Req 21)
-- campaigns already exists in V1; these columns are added additively.
-- ---------------------------------------------------------------------
CALL adpilot_add_column_if_missing('campaigns', 'hosting_enabled', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('campaigns', 'hosting_goal', 'VARCHAR(40) NULL');
CALL adpilot_add_column_if_missing('campaigns', 'target_acos', 'DECIMAL(10,4) NULL');
CALL adpilot_add_column_if_missing('campaigns', 'ai_managed', 'TINYINT(1) NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('campaigns', 'portfolio_id', 'CHAR(36) NULL');
-- FK -> ad_portfolios(id)

CALL adpilot_create_index_if_missing('campaigns', 'idx_campaigns_hosting', '(store_id, hosting_enabled)');

-- ---------------------------------------------------------------------
-- Ad Portfolios (Req 20)
-- campaigns reference a portfolio via campaigns.portfolio_id (added above).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ad_portfolios (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    state VARCHAR(20) DEFAULT 'enabled',          -- 投放状�?
    budget_type VARCHAR(20) DEFAULT 'none',       -- none|recurring|date_range; none => 无预算上�?
    budget DECIMAL(18,4) NULL,
    start_date VARCHAR(10) NULL,
    end_date VARCHAR(10) NULL,
    external_id VARCHAR(100) NULL,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('ad_portfolios', 'idx_ad_portfolios_store', '(store_id)');



-- =====================================================================
-- ===  V3__ai_workitems.sql  ===
-- =====================================================================

-- =====================================================================
-- V3__ai_workitems.sql  (ADDITIVE)
-- AI work-items schema �?AI Notifications (Req 23), Smart Diagnosis (Req 22),
-- and Ad Placement Lock (Req 26).
--
-- This migration is purely additive; V1__init_schema.sql is never edited.
-- Version slot: V3 is reserved for the AI advertising work-items module
-- (V2__ai_advertising_module.sql is already applied; V4__keyword_rank_creative.sql
-- is reserved for keyword library / rank monitoring / creative assets).
--
-- MySQL 8.0 conventions (mirroring V1):
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--     (updated_at also ON UPDATE CURRENT_TIMESTAMP(3))
--   * money -> DECIMAL(18,4); rates/percentages -> DECIMAL(10,4)
--   * JSON for structured blobs; status enums -> VARCHAR
--   * open-dedup uniqueness via a generated column populated only while the
--     item is open and NULL once closed (NULLs are not compared by MySQL
--     unique indexes) -- same pattern as the V1 `alerts` table.
-- =====================================================================

-- ---------------------------------------------------------------------
-- ai_notifications (Req 23.1, 23.2, 23.3, 23.4)
-- Work items the AI advertising module raises for operator attention, in one
-- of four categories, each in a pending(待处�? or closed(已结�? state.
-- Dedups to one PENDING notification per (store, category, subject) via the
-- generated open_dedup_key (populated only while pending, NULL once closed),
-- so closed history is retained without limit (mirrors the V1 alerts pattern).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_notifications (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    category VARCHAR(40) NOT NULL,                -- core_ops|one_click_optimize|high_potential|target_correction
    title VARCHAR(255) NOT NULL,
    detail_json JSON NULL,                        -- payload (proposed change, affected object, metrics)
    subject_id VARCHAR(255) NULL,                 -- campaign/target id the item concerns
    state VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending(待处�?|closed(已结�?
    resolution VARCHAR(20) NULL,                  -- applied|confirmed|rejected|dismissed
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    closed_at DATETIME(3) NULL,
    open_dedup_key VARCHAR(512) GENERATED ALWAYS AS (
        CASE WHEN state = 'pending'
             THEN CONCAT(store_id, ':', category, ':', COALESCE(subject_id, ''))
             ELSE NULL
        END
    ) STORED,
    UNIQUE KEY uk_open_ai_notification (open_dedup_key)
);

CALL adpilot_create_index_if_missing('ai_notifications', 'idx_ai_notifications_store', '(store_id, category, state)');

-- ---------------------------------------------------------------------
-- ai_notification_config (Req 23.5, 23.6)
-- Per-store configuration controlling which core-ops items are raised.
-- One config row per store (uk_ai_notif_config_store).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_notification_config (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    config_json JSON NOT NULL,                    -- which core-ops items are raised
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ai_notif_config_store (store_id)
);

-- ---------------------------------------------------------------------
-- smart_diagnosis_tasks (Req 22.1, 22.2, 22.3, 22.4)
-- A per-product (parent ASIN) diagnosis task with an update frequency, a
-- creator, a last-diagnosis time, and a JSON diagnosis result.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS smart_diagnosis_tasks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    parent_asin VARCHAR(20) NOT NULL,
    update_frequency VARCHAR(20) DEFAULT 'manual',-- manual|daily|weekly
    status VARCHAR(20) DEFAULT 'pending',         -- pending|running|completed|failed
    result_json JSON NULL,                        -- diagnosis result
    created_by CHAR(36) REFERENCES users(id),
    last_diagnosed_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('smart_diagnosis_tasks', 'idx_smart_diagnosis_store', '(store_id)');

-- ---------------------------------------------------------------------
-- ad_placement_lock_strategies (Req 26.1, 26.2, 26.4)
-- A placement strategy targeting a specific Amazon ad placement for an SP
-- campaign using a keyword bid range (bid_min <= bid_max enforced in service).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ad_placement_lock_strategies (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    target_placement VARCHAR(40) NOT NULL,        -- top_of_search_1_1 | page1_5_8 | ...
    bid_min DECIMAL(18,4) NOT NULL,
    bid_max DECIMAL(18,4) NOT NULL,               -- CHECK bid_min <= bid_max enforced in service
    status VARCHAR(20) DEFAULT 'active',
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- ---------------------------------------------------------------------
-- ad_placement_lock_tasks (Req 26.2, 26.3)
-- Per-keyword enforcement tasks generated for a placement-lock strategy; the
-- scheduled evaluator records the last bid it applied and when it last ran.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ad_placement_lock_tasks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    strategy_id CHAR(36) NOT NULL REFERENCES ad_placement_lock_strategies(id) ON DELETE CASCADE,
    keyword_id CHAR(36) NULL,
    last_bid DECIMAL(18,4) NULL,
    last_run_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('ad_placement_lock_strategies', 'idx_placement_lock_store', '(store_id, status)');



-- =====================================================================
-- ===  V4__keyword_rank_creative.sql  ===
-- =====================================================================

-- =====================================================================
-- V4__keyword_rank_creative.sql  (ADDITIVE)
-- Keyword Library + recommendations (Req 27), Rank Monitoring (Req 28),
-- and Creative Asset Library (Req 29).
--
-- This migration is purely additive; V1__init_schema.sql is never edited.
-- Version slot: V4 is reserved for the keyword library / rank monitoring /
-- creative assets surfaces (V2__ai_advertising_module.sql and
-- V3__ai_workitems.sql are already applied; V5/V6 carry the Feishu
-- notification rules and automation rule templates respectively).
--
-- MySQL 8.0 conventions (mirroring V1/V3):
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--   * money -> DECIMAL(18,4); rates/percentages -> DECIMAL(10,4)
--   * JSON for structured blobs; status enums -> VARCHAR
-- =====================================================================

-- ---------------------------------------------------------------------
-- keyword_libraries (Req 27.1, 27.2, 27.3, 27.5)
-- A managed collection of keywords (词库) associated with products, with a
-- library type (词库类型) and an execution schedule. Feeds keyword harvesting
-- and negation AI actions. last_run_at / next_run_at surface the last and
-- next execution times rendered on the 词库 tab.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS keyword_libraries (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    library_type VARCHAR(40) NOT NULL,            -- 词库类型: harvest|negative|brand|competitor
    schedule_cron VARCHAR(64) NULL,               -- execution schedule
    last_run_at DATETIME(3) NULL,                 -- last execution time
    next_run_at DATETIME(3) NULL,                 -- next execution time
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- ---------------------------------------------------------------------
-- keyword_library_items (Req 27.2)
-- The keywords contained in a library, optionally associated with a product.
-- Keyword count (关键词数�? and associated products (关联商品) are derived from
-- these rows. Cascade-deleted with the parent library.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS keyword_library_items (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    library_id CHAR(36) NOT NULL REFERENCES keyword_libraries(id) ON DELETE CASCADE,
    product_id CHAR(36) NULL REFERENCES products(id),
    keyword_text VARCHAR(255) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('keyword_libraries', 'idx_keyword_libraries_store', '(store_id)');
CALL adpilot_create_index_if_missing('keyword_library_items', 'idx_keyword_library_items_library', '(library_id)');

-- ---------------------------------------------------------------------
-- rank_monitor_tasks (Req 28.1, 28.2, 28.4)
-- A keyword rank-monitoring task scoped to a store, optionally tied to a
-- product. Consumed quota = COUNT(active rank_monitor_tasks) for the store;
-- quota enforcement is performed in the service layer.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rank_monitor_tasks (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    product_id CHAR(36) NULL REFERENCES products(id),
    keyword_text VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- ---------------------------------------------------------------------
-- rank_monitor_snapshots (Req 28.2)
-- Point-in-time captures of a monitored keyword's organic rank (自然排名) and
-- ad rank (广告排名). Cascade-deleted with the parent task.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rank_monitor_snapshots (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    task_id CHAR(36) NOT NULL REFERENCES rank_monitor_tasks(id) ON DELETE CASCADE,
    organic_rank INT NULL,                        -- 自然排名
    ad_rank INT NULL,                             -- 广告排名
    captured_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('rank_monitor_tasks', 'idx_rank_monitor_store', '(store_id, status)');
CALL adpilot_create_index_if_missing('rank_monitor_snapshots', 'idx_rank_monitor_snapshots_task', '(task_id)');

-- ---------------------------------------------------------------------
-- creative_assets (Req 29.1, 29.2, 29.3, 29.4)
-- A searchable library of images and videos reusable in ads. asset_type maps
-- to the SparkX creative categories (生活方式�?场景�?高清图组/营销宣传�?;
-- storage_url is produced by FileStorageUtils on upload. Searchable by name,
-- tag, ASIN, and creator.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS creative_assets (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(40) NOT NULL,              -- lifestyle|scene|hd_group|marketing
    media_kind VARCHAR(20) NOT NULL,              -- image|video
    storage_url VARCHAR(1024) NOT NULL,           -- from FileStorageUtils
    asin VARCHAR(20) NULL,
    tags JSON NULL,
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('creative_assets', 'idx_creative_assets_store', '(store_id, asset_type)');



-- =====================================================================
-- ===  V5__feishu_notification_rules.sql  ===
-- =====================================================================

-- Feishu notification rules (additive)
-- V2/V3/V4 are reserved for the AI advertising module migrations
-- (V2__ai_advertising_module.sql, V3__ai_workitems.sql, V4__keyword_rank_creative.sql).
-- This Feishu-specific additive migration uses V5 to avoid collision.
--
-- Backs the GET/POST /api/integrations/feishu/{id}/notification-rules endpoints (Req 10.4).
-- A notification rule binds a Feishu integration (optionally scoped to a store and a target
-- chat) to an event type that should trigger a push, with an optional JSON condition.

CREATE TABLE IF NOT EXISTS feishu_notification_rules (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    feishu_integration_id CHAR(36) NOT NULL REFERENCES feishu_integrations(id) ON DELETE CASCADE,
    store_id CHAR(36) REFERENCES stores(id),
    chat_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    condition_json TEXT,
    enabled TINYINT(1) DEFAULT 1,
    status VARCHAR(30) DEFAULT 'active',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('feishu_notification_rules', 'idx_feishu_notification_rules_integration', '(feishu_integration_id)');
CALL adpilot_create_index_if_missing('feishu_notification_rules', 'idx_feishu_notification_rules_store', '(store_id)');



-- =====================================================================
-- ===  V6__automation_rule_templates.sql  ===
-- =====================================================================

-- =====================================================================
-- V6__automation_rule_templates.sql  (ADDITIVE)
-- Automation Rule Templates �?condition-to-action (Req 9, 25).
--
-- This migration is purely additive; V1__init_schema.sql is never edited.
-- Version slot: V2/V3/V4 are reserved for the AI advertising module
-- (V2__ai_advertising_module.sql exists; V3__ai_workitems.sql and
-- V4__keyword_rank_creative.sql are reserved), and V5 is taken by
-- V5__feishu_notification_rules.sql. V2 is already applied and must not be
-- edited, so the rule-template tables land in the next free slot, V6.
--
-- MySQL 8.0 conventions (mirroring V1):
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--     (updated_at also ON UPDATE CURRENT_TIMESTAMP(3))
--   * JSON for structured blobs; status enums -> VARCHAR
-- =====================================================================

-- ---------------------------------------------------------------------
-- automation_rule_templates (Req 25.1, 25.2)
-- A reusable condition-to-action template scoped to a store. The evaluator
-- (RuleTemplateEvaluator) checks condition_json against each linked object's
-- metrics on the scheduler cadence and applies action_json when it holds.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS automation_rule_templates (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    name VARCHAR(255) NOT NULL,                   -- 模板名称
    template_type VARCHAR(50) NOT NULL,           -- 模板类型: bid_adjustment|negative_keyword|budget|dayparting
    condition_json JSON NOT NULL,                 -- condition tree
    action_json JSON NOT NULL,                    -- action (bid delta / add negative / etc.)
    status VARCHAR(20) DEFAULT 'enabled',         -- enabled|disabled
    created_by CHAR(36) REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- ---------------------------------------------------------------------
-- automation_rule_template_links (Req 25.3)
-- Links a template to the campaigns / targets / keywords it governs. A given
-- object is linked to a template at most once (uk_template_object).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS automation_rule_template_links (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    template_id CHAR(36) NOT NULL REFERENCES automation_rule_templates(id) ON DELETE CASCADE,
    object_type VARCHAR(30) NOT NULL,             -- campaign|target|keyword
    object_id CHAR(36) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_template_object (template_id, object_type, object_id)
);

CALL adpilot_create_index_if_missing('automation_rule_templates', 'idx_rule_templates_store', '(store_id, status)');



-- =====================================================================
-- ===  V7__ai_feature_improvements.sql  (ADDITIVE)  ===
-- =====================================================================
-- Additive tables for the AI-feature improvement round:
--   * insight_agent_insights  — persist every generated Insight Agent result
--     until manually deleted (item 16).
--   * data_source_activation  — per-store activation flag that unlocks the
--     brand / SQP / AMC Data Insights surfaces to compute from stored data
--     (item 8). Activation never fabricates Amazon-side data.
--
-- This migration is purely additive; earlier migrations are never edited.
-- Version slot: V7 is the next free slot (V1..V6 are taken).
--
-- MySQL 8.0 conventions (mirroring V1/V3):
--   * id/FK columns -> CHAR(36); primary keys DEFAULT (UUID())
--   * created_at/updated_at -> DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
--     (updated_at also ON UPDATE CURRENT_TIMESTAMP(3))
--   * JSON for structured blobs; status/enum-like values -> VARCHAR
--   * boolean flags -> TINYINT(1)
-- =====================================================================

-- ---------------------------------------------------------------------
-- insight_agent_insights (item 16)
-- Persisted Insight Agent results. Every generated insight is stored and
-- retained until the operator manually deletes it. Listed newest-first per
-- store on the Insight Agent page so history survives reloads.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS insight_agent_insights (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NULL REFERENCES stores(id),
    query VARCHAR(2000) NOT NULL,                 -- the operator's question
    source VARCHAR(40) NULL,                       -- analysis source (all|ads|listing|keyword)
    premium TINYINT(1) NOT NULL DEFAULT 0,         -- whether Premium mode was applied
    insights MEDIUMTEXT NOT NULL,                  -- narrative insights produced
    recommended_actions JSON NULL,                 -- recommended next actions (string list)
    generated_by VARCHAR(20) NULL,                 -- ai|degraded
    created_by CHAR(36) NULL REFERENCES users(id),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

CALL adpilot_create_index_if_missing('insight_agent_insights', 'idx_insight_agent_insights_store', '(store_id, created_at)');

-- ---------------------------------------------------------------------
-- data_source_activation (item 8)
-- Per-store activation flag for an external data source (e.g. brand analytics
-- / AMC). When activated, the brand / SQP / AMC surfaces compute from
-- available stored data (or show an empty state) instead of the
-- "requires activation" gate. Activation only unlocks the surfaces to use
-- stored data; it never fabricates Amazon-side data.
-- One row per (store, source) via uk_data_source_activation.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_source_activation (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    source VARCHAR(40) NOT NULL,                   -- brand_analytics|amc
    activated TINYINT(1) NOT NULL DEFAULT 0,
    activated_by CHAR(36) NULL REFERENCES users(id),
    activated_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_data_source_activation (store_id, source)
);

CALL adpilot_create_index_if_missing('data_source_activation', 'idx_data_source_activation_store', '(store_id)');


-- ---------------------------------------------------------------------
-- Feishu webhook quick-connect (additive).
-- The feishu_integrations CREATE TABLE above already carries these columns
-- for fresh installs. For existing DBs run the idempotent statements below
-- to add webhook quick-connect support and relax app credentials to NULL
-- (webhook connections carry no app_id/app_secret):
--
-- ALTER TABLE feishu_integrations ADD COLUMN connection_type VARCHAR(20) DEFAULT 'app';
-- ALTER TABLE feishu_integrations ADD COLUMN webhook_url_encrypted TEXT NULL;
-- ALTER TABLE feishu_integrations ADD COLUMN webhook_secret_encrypted TEXT NULL;
-- ALTER TABLE feishu_integrations MODIFY COLUMN app_id VARCHAR(255) NULL;
-- ALTER TABLE feishu_integrations MODIFY COLUMN app_secret_encrypted TEXT NULL;
-- ---------------------------------------------------------------------


-- =====================================================================
-- ===  platform-ux-logistics-enhancements (Req 19) ====================
-- =====================================================================
-- Logistics / FBA shipment-depth child tables + managed carriers.
-- Conventions match the existing `shipments` / `shipment_items` tables:
--   * CHAR(36) UUID PKs with DEFAULT (UUID())
--   * DATETIME(3) timestamps (created_at/updated_at as elsewhere)
--   * DECIMAL money columns
--   * shipment children REFERENCES shipments(id) ON DELETE CASCADE and
--     inherit store scope via shipment_id -> shipments.store_id (Req 17.2)
--   * carriers are org-scoped via org_id -> organizations(id) (Req 17.1)
-- Added here (the single source of truth); no Flyway / ddl-auto (Req 19.2).
-- =====================================================================

-- Carriers (org-scoped managed carrier directory; referenced by legs)
CREATE TABLE IF NOT EXISTS carriers (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    name VARCHAR(200) NOT NULL,
    service_type VARCHAR(100) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Shipment Legs (multi-leg transport path; ordered by sequence_no)
CREATE TABLE IF NOT EXISTS shipment_legs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    leg_type VARCHAR(50) NOT NULL,
    sequence_no INT NOT NULL,
    carrier_id CHAR(36) REFERENCES carriers(id),
    departure_date DATE,
    arrival_date DATE,
    leg_cost DECIMAL(14,2) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Carton Specifications (per-shipment box dimensions / weight / counts)
CREATE TABLE IF NOT EXISTS carton_specs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    box_length_cm DECIMAL(6,1) DEFAULT 0,
    box_width_cm DECIMAL(6,1) DEFAULT 0,
    box_height_cm DECIMAL(6,1) DEFAULT 0,
    box_weight_kg DECIMAL(8,2) DEFAULT 0,
    units_per_box INT DEFAULT 0,
    box_count INT DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Customs Clearance (one record per shipment)
CREATE TABLE IF NOT EXISTS customs_clearance (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    clearance_status VARCHAR(30) NOT NULL DEFAULT 'not_started',
    declaration_ref VARCHAR(100),
    duties_taxes DECIMAL(14,2) DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_customs_clearance_shipment (shipment_id)
);

-- Tracking Events (trajectory; leg_id nullable; recorded_at is tiebreaker)
CREATE TABLE IF NOT EXISTS tracking_events (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    leg_id CHAR(36) NULL REFERENCES shipment_legs(id),
    event_time DATETIME(3) NOT NULL,
    recorded_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    description VARCHAR(500) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Shipment Exceptions (open -> resolved one-way transition)
CREATE TABLE IF NOT EXISTS shipment_exceptions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    exception_type VARCHAR(30) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    resolution_state VARCHAR(20) NOT NULL DEFAULT 'open',
    resolved_by CHAR(36) NULL REFERENCES users(id),
    resolved_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Handling Costs (per-shipment handling-cost lines; multi-currency provenance)
CREATE TABLE IF NOT EXISTS handling_costs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    amount DECIMAL(14,2) DEFAULT 0,
    currency_code CHAR(3) NOT NULL,
    description VARCHAR(200) NOT NULL,
    exchange_rate DECIMAL(18,8) NULL,
    cost_date DATE NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Shipment Line Items (FBA SKU/MSKU/ASIN line items)
CREATE TABLE IF NOT EXISTS shipment_line_items (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    shipment_id CHAR(36) NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    asin VARCHAR(20),
    msku VARCHAR(100),
    quantity INT DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
CALL adpilot_create_index_if_missing('carriers', 'idx_carriers_org', '(org_id)');
CALL adpilot_create_index_if_missing('shipment_legs', 'idx_shipment_legs_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('shipment_legs', 'idx_shipment_legs_sequence', '(shipment_id, sequence_no)');
CALL adpilot_create_index_if_missing('shipment_legs', 'idx_shipment_legs_carrier', '(carrier_id)');
CALL adpilot_create_index_if_missing('carton_specs', 'idx_carton_specs_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('customs_clearance', 'idx_customs_clearance_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('tracking_events', 'idx_tracking_events_shipment', '(shipment_id, event_time)');
CALL adpilot_create_index_if_missing('tracking_events', 'idx_tracking_events_leg', '(leg_id)');
CALL adpilot_create_index_if_missing('shipment_exceptions', 'idx_shipment_exceptions_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('shipment_exceptions', 'idx_shipment_exceptions_state', '(resolution_state)');
CALL adpilot_create_index_if_missing('handling_costs', 'idx_handling_costs_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('shipment_line_items', 'idx_shipment_line_items_shipment', '(shipment_id)');
CALL adpilot_create_index_if_missing('shipment_line_items', 'idx_shipment_line_items_sku', '(sku)');

-- =====================================================================
-- Reusable table-view capabilities: saved views + column configuration.
-- Conventions match the existing tables:
--   * CHAR(36) UUID PKs with DEFAULT (UUID())
--   * DATETIME(3) timestamps (created_at/updated_at as elsewhere)
--   * config stored as JSON
-- Keyed by user and store-independent (Req 17.5): scoped to (user_id, table_key);
-- never readable by another user. Added to the single source of truth;
-- no Flyway / ddl-auto (Req 19.2).
-- =====================================================================

-- Saved Views — per-user named table configurations (columns + filters + sort)
CREATE TABLE IF NOT EXISTS saved_views (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    table_key VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    config JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_saved_views_user_table_name UNIQUE (user_id, table_key, name)
);

-- Column Configurations — per-user column visibility/order/pin per table
CREATE TABLE IF NOT EXISTS column_configs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    user_id CHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    table_key VARCHAR(100) NOT NULL,
    config JSON DEFAULT (JSON_OBJECT()),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_column_configs_user_table UNIQUE (user_id, table_key)
);

-- Indexes
CALL adpilot_create_index_if_missing('saved_views', 'idx_saved_views_user_table', '(user_id, table_key)');
CALL adpilot_create_index_if_missing('column_configs', 'idx_column_configs_user_table', '(user_id, table_key)');

-- =====================================================================
-- ===  advertising-workspace-rework  ==================================
-- =====================================================================
-- Operation model (Operation_Record, Pending_Overlay, Outbox), executable
-- AI personality policies, the three migration-exception lists, and the
-- campaign↔parent-ASIN association table.
--
-- Added to the single source of truth (no Flyway / ddl-auto = none, Req 51.8).
-- Conventions match the existing tables:
--   * CHAR(36) UUID PKs with DEFAULT (UUID())
--   * DATETIME(3) timestamps (created_at; updated_at ON UPDATE where mutable)
--   * JSON for structured payloads / before-after values
--   * indexes declared separately with CREATE INDEX
-- A matching rollback (DROP) block is provided at the very end of this
-- section so the change is reversible (Req 51.8).
-- =====================================================================

-- Operation_Record — the authoritative record of every write Operation
-- (Req 8). Separates execution_status (local_configuration) from sync_state
-- (platform_mutation lifecycle). operation_source and operation_scope are
-- immutable once written (enforced in the service layer, Req 12.6).
CREATE TABLE IF NOT EXISTS operations (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    -- IMMUTABLE: manual / recommendation / one_click_optimize / ai_hosting / creation
    operation_source VARCHAR(20) NOT NULL,
    -- platform_mutation / local_configuration
    operation_scope VARCHAR(20) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    -- writable field changed; nullable for multi-field operations
    field VARCHAR(40) NULL,
    -- stable across attempts of the same logical change (Req 5, 7.8)
    logical_operation_id CHAR(36) NOT NULL,
    -- click coalescing key, unique-ish per logical change (Req 5.3)
    logical_idempotency_key VARCHAR(120) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    -- per platform submission; rotated on retry (Req 5.2, 5.7)
    submission_idempotency_key VARCHAR(120) NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    -- publish -> local-only draft link (Req 12.10); null otherwise
    parent_operation_id CHAR(36) NULL,
    -- Amazon-confirmed value at creation
    before_value JSON,
    -- requested pending value
    after_value JSON,
    -- undo eligibility (Req 8.4)
    reversible TINYINT(1) NOT NULL DEFAULT 0,
    -- bulk affected object count
    affected_count INT NOT NULL DEFAULT 1,
    -- acting user from security context (Req 24.4)
    acting_user_id CHAR(36) REFERENCES users(id),
    -- platform_mutation lifecycle; null for local_configuration
    sync_state VARCHAR(30) NULL,
    -- local_configuration only: applied / failed / cancelled
    execution_status VARCHAR(20) NULL,
    -- connector correlation reference (Req 55.7)
    platform_reference VARCHAR(120) NULL,
    -- platform result payload where provided
    platform_result JSON,
    -- reason for failed / cancelled / expired / cancel_requested / reconciliation_required
    status_reason VARCHAR(500) NULL,
    -- Req 33.1: pre-submission revalidation expiry timestamp (configurable TTL, default 4h)
    decision_expires_at DATETIME(3) NULL,
    -- AI decision rule version (Req 49.9)
    personality_rule_version VARCHAR(40) NULL,
    -- trigger metric, resolved personality, allowed/actual magnitude, reason, predicted impact
    ai_decision JSON,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- operation_pending_changes — the open row backs the Pending_Overlay for an
-- Unsettled_State Operation (Req 7). No second physical column per field.
CREATE TABLE IF NOT EXISTS operation_pending_changes (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    operation_id CHAR(36) NOT NULL REFERENCES operations(id),
    entity_type VARCHAR(40) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    field VARCHAR(40) NOT NULL,
    before_value JSON,
    after_value JSON,
    -- open / closed
    status VARCHAR(20) NOT NULL DEFAULT 'open',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- operation_outbox — written in the SAME transaction as the Operation; never
-- written for local_configuration. Claimed by the OutboxWorker with
-- SELECT ... FOR UPDATE SKIP LOCKED / version-claim (Req 6).
CREATE TABLE IF NOT EXISTS operation_outbox (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    operation_id CHAR(36) NOT NULL REFERENCES operations(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    platform VARCHAR(40) NOT NULL,
    payload JSON,
    submission_idempotency_key VARCHAR(120) NULL,
    -- pending / claimed / submitted / done / failed
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    attempt_count INT NOT NULL DEFAULT 0,
    claimed_at DATETIME(3) NULL,
    next_attempt_at DATETIME(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- personality_policies — one row per (scope, personality) carrying the concrete
-- numeric control fields and the in-effect rule_version (Req 49.5 / 49.6).
CREATE TABLE IF NOT EXISTS personality_policies (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    -- policy scope (e.g. system / store / campaign) and the personality it parameterizes
    scope VARCHAR(20) NOT NULL,
    scope_id CHAR(36) NULL,
    personality VARCHAR(20) NOT NULL,
    -- status and validity period
    status VARCHAR(12) NOT NULL DEFAULT 'active',
    effective_from DATETIME(3) NULL,
    effective_to DATETIME(3) NULL,
    -- candidate-eligibility thresholds
    min_clicks INT NOT NULL DEFAULT 0,
    min_orders INT NOT NULL DEFAULT 0,
    lookback_days INT NOT NULL DEFAULT 30,
    min_conversion_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
    negative_confidence_threshold DECIMAL(10,6) NOT NULL DEFAULT 0,
    acos_tolerance_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    -- approval gating ratios (Req 49.5 / 22.8)
    approval_bid_change_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    approval_budget_change_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    -- Safety_Boundary maximum ratios (Req 49.10 / 49.11)
    max_bid_increase_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    max_bid_decrease_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    max_daily_budget_increase_ratio DECIMAL(10,6) NOT NULL DEFAULT 0,
    adjustment_cooldown_hours INT NOT NULL DEFAULT 0,
    -- explore budget band
    explore_budget_ratio_min DECIMAL(10,6) NOT NULL DEFAULT 0,
    explore_budget_ratio_max DECIMAL(10,6) NOT NULL DEFAULT 0,
    -- V3 keyword/negative control fields (Req 54.3)
    max_new_keywords_per_run INT NOT NULL DEFAULT 0,
    min_keyword_orders INT NOT NULL DEFAULT 0,
    negative_keyword_min_clicks INT NOT NULL DEFAULT 0,
    -- in-effect rule version recorded on every AI Operation (Req 49.9)
    rule_version VARCHAR(40) NOT NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_pp_scope UNIQUE (scope, scope_id, personality, rule_version)
);

-- object_status_migration_exceptions — unknown/ambiguous Object_Status values
-- flagged for manual review; never auto-mapped (Req 16.6).
CREATE TABLE IF NOT EXISTS object_status_migration_exceptions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    table_name VARCHAR(100) NOT NULL,
    column_name VARCHAR(100) NOT NULL,
    record_id CHAR(36) NOT NULL,
    original_value VARCHAR(255),
    reason VARCHAR(500),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- acos_migration_exceptions — ACoS values whose stored scale is ambiguous and
-- cannot be safely converted to the decimal-ratio scale (Req 17.6).
CREATE TABLE IF NOT EXISTS acos_migration_exceptions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    table_name VARCHAR(100) NOT NULL,
    column_name VARCHAR(100) NOT NULL,
    record_id CHAR(36) NOT NULL,
    original_value VARCHAR(255),
    reason VARCHAR(500),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- optimization_goal_migration_exceptions — legacy goal-type / hosting-goal
-- values that do not map to the new Optimization_Goal enum (Req 57.3).
CREATE TABLE IF NOT EXISTS optimization_goal_migration_exceptions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    table_name VARCHAR(100) NOT NULL,
    column_name VARCHAR(100) NOT NULL,
    record_id CHAR(36) NOT NULL,
    original_value VARCHAR(255),
    reason VARCHAR(500),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- campaign_product_links — persisted source for the parent-ASIN / targeting-goal
-- server-side filters (Req 15.4). Until populated, those filters are out of
-- scope and the frontend does not offer them (Req 15.5).
CREATE TABLE IF NOT EXISTS campaign_product_links (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    parent_asin VARCHAR(20),
    product_id CHAR(36) REFERENCES products(id),
    targeting_goal VARCHAR(40),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Indexes
-- operations: in-flight conflict lock + overlay + idempotency lookups (Req 5, 7)
CALL adpilot_create_index_if_missing('operations', 'idx_operations_store_state', '(store_id, sync_state)');
CALL adpilot_create_index_if_missing('operations', 'idx_operations_entity_state', '(entity_type, entity_id, sync_state)');
CALL adpilot_create_index_if_missing('operations', 'idx_operations_logical', '(logical_operation_id)');
CALL adpilot_create_index_if_missing('operations', 'idx_operations_logical_key', '(logical_idempotency_key)');
CALL adpilot_create_index_if_missing('operations', 'idx_operations_submission_key', '(submission_idempotency_key)');
CALL adpilot_create_index_if_missing('operations', 'idx_operations_parent', '(parent_operation_id)');
-- operation_pending_changes: overlay read by (entity, field) for open rows
CALL adpilot_create_index_if_missing('operation_pending_changes', 'idx_op_pending_entity_field', '(entity_type, entity_id, field)');
CALL adpilot_create_index_if_missing('operation_pending_changes', 'idx_op_pending_operation', '(operation_id)');
CALL adpilot_create_index_if_missing('operation_pending_changes', 'idx_op_pending_status', '(status)');
-- operation_outbox: worker claim scan + correlation
CALL adpilot_create_index_if_missing('operation_outbox', 'idx_op_outbox_status', '(status)');
CALL adpilot_create_index_if_missing('operation_outbox', 'idx_op_outbox_operation', '(operation_id)');
CALL adpilot_create_index_if_missing('operation_outbox', 'idx_op_outbox_store_platform', '(store_id, platform)');
-- migration exception lists: review by column
CALL adpilot_create_index_if_missing('object_status_migration_exceptions', 'idx_obj_status_mig_exc_col', '(table_name, column_name)');
CALL adpilot_create_index_if_missing('acos_migration_exceptions', 'idx_acos_mig_exc_col', '(table_name, column_name)');
CALL adpilot_create_index_if_missing('optimization_goal_migration_exceptions', 'idx_opt_goal_mig_exc_col', '(table_name, column_name)');
-- campaign_product_links: parent-ASIN / targeting-goal server-side filters
CALL adpilot_create_index_if_missing('campaign_product_links', 'idx_campaign_product_links_campaign_asin', '(campaign_id, parent_asin)');
CALL adpilot_create_index_if_missing('campaign_product_links', 'idx_campaign_product_links_store_asin', '(store_id, parent_asin)');
-- campaigns: Amazon-synced-list gating by amazon_campaign_id (Req 12.7)
CALL adpilot_create_index_if_missing('campaigns', 'idx_campaigns_amazon_campaign_id', '(amazon_campaign_id)');

-- =====================================================================
-- Seed: personality_policies system defaults (Req 49.5 / 49.6)
-- One row per (scope, personality) at scope='system'. These are the
-- configurable backend defaults the optimizer falls back to; each
-- approval ratio is set strictly below its corresponding maximum ratio
-- so approval can trigger before the maximum is reached (Req 49.5).
-- Fixed ids + INSERT IGNORE keep a fresh import idempotent (the unique
-- (scope, personality) constraint also protects against duplicates).
-- NOTE: the schema's V3 keyword columns (max_new_keywords_per_run,
-- min_keyword_orders, negative_keyword_min_clicks; tagged Req 54.3) do
-- not line up name-for-name with the Req 49.5 keyword fields
-- (keywordExpansionMode / negativeKeywordMode / keywordConfidenceThreshold
-- / maxKeywordsAddedPerDay / maxNegativesAddedPerDay), so they are seeded
-- with the closest-matching Req 49.5 values: max_new_keywords_per_run
-- <- maxKeywordsAddedPerDay, min_keyword_orders <- minOrders,
-- negative_keyword_min_clicks <- minClicks. rule_version seeded as 'v1'.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO personality_policies (
    id, scope, personality,
    min_clicks, min_orders, lookback_days, min_conversion_rate,
    negative_confidence_threshold, acos_tolerance_ratio,
    approval_bid_change_ratio, approval_budget_change_ratio,
    max_bid_increase_ratio, max_bid_decrease_ratio, max_daily_budget_increase_ratio,
    adjustment_cooldown_hours, explore_budget_ratio_min, explore_budget_ratio_max,
    max_new_keywords_per_run, min_keyword_orders, negative_keyword_min_clicks,
    rule_version
) VALUES
('00000000-0000-0000-0000-000000004901', 'system', 'conservative',
    50, 5, 30, 0.100000,
    0.900000, 0.050000,
    0.030000, 0.030000,
    0.050000, 0.100000, 0.050000,
    24, 0.000000, 0.050000,
    0, 5, 50,
    'v1'),
('00000000-0000-0000-0000-000000004902', 'system', 'balanced',
    20, 3, 14, 0.070000,
    0.750000, 0.150000,
    0.070000, 0.100000,
    0.100000, 0.150000, 0.150000,
    12, 0.050000, 0.150000,
    5, 3, 20,
    'v1'),
('00000000-0000-0000-0000-000000004903', 'system', 'aggressive',
    10, 1, 7, 0.030000,
    0.600000, 0.300000,
    0.150000, 0.200000,
    0.200000, 0.250000, 0.300000,
    4, 0.150000, 0.300000,
    20, 1, 10,
    'v1');

-- =====================================================================
-- ===  Amazon Ads AI Hosting System — New Tables  =====================
-- =====================================================================
-- Added by the amazon-ads-ai-hosting-system spec (Task 1.2).
-- All tables follow established conventions: CHAR(36) UUID PKs with
-- DEFAULT (UUID()), DATETIME(3) timestamps, JSON for structured payloads,
-- and indexes declared separately. Every table is org-scopeable via a
-- non-null store_id (FK to stores) or direct org_id.
-- =====================================================================

-- Req 2.7: per-run report sync ledger
CREATE TABLE IF NOT EXISTS report_sync_runs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    report_type VARCHAR(40) NOT NULL,
    requested_date_start DATE NOT NULL,
    requested_date_end DATE NOT NULL,
    report_status VARCHAR(20) NOT NULL
        CHECK (report_status IN ('requested', 'completed', 'failed', 'expired')),
    data_status VARCHAR(12) NOT NULL
        CHECK (data_status IN ('preliminary', 'finalized')),
    row_count INT NOT NULL DEFAULT 0,
    amazon_report_id VARCHAR(120) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    error VARCHAR(1000) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Req 2.7: report sync failure records
CREATE TABLE IF NOT EXISTS report_sync_errors (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    run_id CHAR(36) NOT NULL REFERENCES report_sync_runs(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    attempt INT NOT NULL DEFAULT 1,
    error VARCHAR(1000) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Req 31.1, 31.2: dedicated daily search-term performance store
CREATE TABLE IF NOT EXISTS search_term_daily (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    campaign_id CHAR(36) NOT NULL,
    ad_group_id CHAR(36) NOT NULL,
    keyword_id CHAR(36) NULL,
    search_term VARCHAR(500) NOT NULL,
    report_date DATE NOT NULL,
    impressions BIGINT DEFAULT 0,
    clicks INT DEFAULT 0,
    orders INT DEFAULT 0,
    spend DECIMAL(18,4) DEFAULT 0,
    sales DECIMAL(18,4) DEFAULT 0,
    acos DECIMAL(10,6) DEFAULT 0,
    ctr DECIMAL(10,6) DEFAULT 0,
    cvr DECIMAL(10,6) DEFAULT 0,
    cpc DECIMAL(10,4) DEFAULT 0,
    currency VARCHAR(10) NULL,
    data_status VARCHAR(12) NOT NULL DEFAULT 'preliminary'
        CHECK (data_status IN ('preliminary', 'finalized')),
    data_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_std UNIQUE (store_id, campaign_id, ad_group_id, search_term, report_date)
);

-- Req 14.6: quarantine for unresolved metric rows
CREATE TABLE IF NOT EXISTS metric_quarantine (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    report_type VARCHAR(40) NOT NULL,
    external_entity_type VARCHAR(50) NULL,
    external_entity_id VARCHAR(255) NULL,
    report_date DATE NULL,
    raw_row JSON,
    reason VARCHAR(200) NULL,
    resolved TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Req 6.7, 6.2: typed multi-level safety boundaries
CREATE TABLE IF NOT EXISTS safety_boundaries (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NULL REFERENCES stores(id),
    org_id CHAR(36) NULL REFERENCES organizations(id),
    scope VARCHAR(20) NOT NULL
        CHECK (scope IN ('campaign', 'goal', 'store', 'organization', 'system')),
    scope_id CHAR(36) NULL,
    limit_type VARCHAR(60) NOT NULL,
    value_type VARCHAR(12) NOT NULL
        CHECK (value_type IN ('amount', 'ratio', 'integer', 'boolean')),
    value_amount DECIMAL(18,4) NULL,
    value_ratio DECIMAL(10,6) NULL,
    value_integer INT NULL,
    value_boolean TINYINT(1) NULL,
    comparison_semantics VARCHAR(20) NOT NULL
        CHECK (comparison_semantics IN ('upper_bound', 'lower_bound', 'boolean_or', 'set_intersection')),
    currency VARCHAR(10) NULL,
    created_by CHAR(36) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_sb UNIQUE (scope, scope_id, limit_type)
);

-- Req 22.1: brand word protection lists per store
CREATE TABLE IF NOT EXISTS brand_word_lists (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    word VARCHAR(255) NOT NULL,
    match_type VARCHAR(10) NOT NULL DEFAULT 'exact'
        CHECK (match_type IN ('exact', 'contains')),
    created_by CHAR(36) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Req 18.1: optimization run ledger (must be created before ai_decisions which references it)
CREATE TABLE IF NOT EXISTS optimization_runs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    trigger_type VARCHAR(12) NOT NULL
        CHECK (trigger_type IN ('scheduled', 'manual')),
    status VARCHAR(12) NOT NULL
        CHECK (status IN ('running', 'completed', 'failed')),
    phase VARCHAR(4) NULL,
    snapshot_ref CHAR(36) NULL,
    campaigns_processed INT DEFAULT 0,
    campaigns_skipped INT DEFAULT 0,
    operations_created INT DEFAULT 0,
    decisions_generated INT DEFAULT 0,
    decisions_auto_executed INT DEFAULT 0,
    decisions_requiring_approval INT DEFAULT 0,
    per_campaign_results JSON,
    skip_reasons JSON,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Req 37.1: AI decisions store — every decision regardless of execution mode
CREATE TABLE IF NOT EXISTS ai_decisions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    run_id CHAR(36) NULL REFERENCES optimization_runs(id),
    campaign_id CHAR(36) NULL,
    engine VARCHAR(20) NOT NULL,
    decision_type VARCHAR(40) NOT NULL,
    execution_mode VARCHAR(20) NOT NULL,
    risk_score DECIMAL(6,5) NOT NULL DEFAULT 0,
    routing_outcome VARCHAR(30) NULL,
    promoted_operation_id CHAR(36) NULL REFERENCES operations(id),
    decision_snapshot JSON NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Req 8.3: effect attribution — post-execution measurement
CREATE TABLE IF NOT EXISTS effect_attributions (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    operation_id CHAR(36) NOT NULL REFERENCES operations(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    metric_type VARCHAR(30) NOT NULL,
    observed_change DECIMAL(18,6) NULL,
    estimated_incremental_impact DECIMAL(18,6) NULL,
    attribution_confidence DECIMAL(6,5) NULL,
    attribution_method VARCHAR(40) NULL,
    method_version VARCHAR(20) NULL,
    measurement_window_start DATETIME(3) NULL,
    measurement_window_end DATETIME(3) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3)
);

-- Req 9.6, 9.7: notification delivery tracking
CREATE TABLE IF NOT EXISTS notification_delivery_log (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NULL REFERENCES stores(id),
    notification_type VARCHAR(40) NOT NULL,
    recipient VARCHAR(255) NULL,
    status VARCHAR(12) NOT NULL
        CHECK (status IN ('delivered', 'failed', 'queued')),
    attempt_count INT NOT NULL DEFAULT 1,
    last_error VARCHAR(1000) NULL,
    payload JSON NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

-- Req 35.3: hosting kill switch state
CREATE TABLE IF NOT EXISTS hosting_kill_switches (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    scope VARCHAR(20) NOT NULL
        CHECK (scope IN ('system', 'organization', 'store', 'campaign')),
    scope_id CHAR(36) NULL,
    store_id CHAR(36) NULL REFERENCES stores(id),
    org_id CHAR(36) NULL REFERENCES organizations(id),
    activated_by CHAR(36) NULL,
    activated_at DATETIME(3) NULL,
    deactivated_by CHAR(36) NULL,
    deactivated_at DATETIME(3) NULL,
    reason VARCHAR(500) NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'inactive')),
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_ks UNIQUE (scope, scope_id)
);

-- Req 12.1, 21.1: hosting configuration per store/goal/campaign
CREATE TABLE IF NOT EXISTS hosting_configs (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    scope VARCHAR(12) NOT NULL
        CHECK (scope IN ('store', 'goal', 'campaign')),
    scope_id CHAR(36) NOT NULL,
    config JSON NULL,
    created_by CHAR(36) NULL,
    updated_by CHAR(36) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_hc UNIQUE (store_id, scope, scope_id)
);

-- Req 35.2: persistent org-level canary rollout controls
CREATE TABLE IF NOT EXISTS hosting_canary_rollouts (
    org_id CHAR(36) PRIMARY KEY REFERENCES organizations(id),
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    created_by CHAR(36) NULL,
    updated_by CHAR(36) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS hosting_canary_stores (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    org_id CHAR(36) NOT NULL REFERENCES organizations(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    created_by CHAR(36) NULL,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_hcs UNIQUE (org_id, store_id)
);

-- Indexes for new hosting tables
CALL adpilot_create_index_if_missing('report_sync_runs', 'idx_rsr_store', '(store_id)');
CALL adpilot_create_index_if_missing('report_sync_runs', 'idx_rsr_status', '(report_status)');
CALL adpilot_create_index_if_missing('report_sync_runs', 'idx_rsr_type_date', '(store_id, report_type, requested_date_start)');
CALL adpilot_create_index_if_missing('report_sync_errors', 'idx_rse_run', '(run_id)');
CALL adpilot_create_index_if_missing('search_term_daily', 'idx_std_store_date', '(store_id, report_date)');
CALL adpilot_create_index_if_missing('search_term_daily', 'idx_std_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('search_term_daily', 'idx_std_search_term', '(search_term)');
CALL adpilot_create_index_if_missing('metric_quarantine', 'idx_mq_store', '(store_id)');
CALL adpilot_create_index_if_missing('metric_quarantine', 'idx_mq_resolved', '(resolved)');
CALL adpilot_create_index_if_missing('metric_quarantine', 'idx_mq_report_type', '(store_id, report_type, report_date)');
CALL adpilot_create_index_if_missing('safety_boundaries', 'idx_sb_store', '(store_id)');
CALL adpilot_create_index_if_missing('safety_boundaries', 'idx_sb_scope', '(scope, scope_id)');
CALL adpilot_create_index_if_missing('brand_word_lists', 'idx_bwl_store', '(store_id)');
CALL adpilot_create_index_if_missing('brand_word_lists', 'idx_bwl_word', '(store_id, word)');
CALL adpilot_create_index_if_missing('optimization_runs', 'idx_or_store', '(store_id)');
CALL adpilot_create_index_if_missing('optimization_runs', 'idx_or_status', '(status)');
CALL adpilot_create_index_if_missing('optimization_runs', 'idx_or_started', '(started_at)');
CALL adpilot_create_index_if_missing('ai_decisions', 'idx_aid_store', '(store_id)');
CALL adpilot_create_index_if_missing('ai_decisions', 'idx_aid_campaign', '(campaign_id)');
CALL adpilot_create_index_if_missing('ai_decisions', 'idx_aid_run', '(run_id)');
CALL adpilot_create_index_if_missing('ai_decisions', 'idx_aid_created', '(created_at)');
CALL adpilot_create_index_if_missing('ai_decisions', 'idx_aid_promoted', '(promoted_operation_id)');
CALL adpilot_create_index_if_missing('effect_attributions', 'idx_ea_operation', '(operation_id)');
CALL adpilot_create_index_if_missing('effect_attributions', 'idx_ea_store', '(store_id)');
CALL adpilot_create_index_if_missing('effect_attributions', 'idx_ea_window', '(measurement_window_start, measurement_window_end)');
CALL adpilot_create_index_if_missing('notification_delivery_log', 'idx_ndl_store', '(store_id)');
CALL adpilot_create_index_if_missing('notification_delivery_log', 'idx_ndl_type', '(notification_type)');
CALL adpilot_create_index_if_missing('notification_delivery_log', 'idx_ndl_status', '(status)');
CALL adpilot_create_index_if_missing('hosting_kill_switches', 'idx_hks_scope', '(scope, scope_id)');
CALL adpilot_create_index_if_missing('hosting_kill_switches', 'idx_hks_store', '(store_id)');
CALL adpilot_create_index_if_missing('hosting_kill_switches', 'idx_hks_status', '(status)');
CALL adpilot_create_index_if_missing('hosting_configs', 'idx_hc_store', '(store_id)');
CALL adpilot_create_index_if_missing('hosting_configs', 'idx_hc_scope', '(scope, scope_id)');
CALL adpilot_create_index_if_missing('hosting_canary_rollouts', 'idx_hcr_enabled', '(enabled)');
CALL adpilot_create_index_if_missing('hosting_canary_stores', 'idx_hcs_org', '(org_id)');
CALL adpilot_create_index_if_missing('hosting_canary_stores', 'idx_hcs_store', '(store_id)');

-- Req 19: campaign learning period tracking
CREATE TABLE IF NOT EXISTS campaign_learning_periods (
    id CHAR(36) PRIMARY KEY DEFAULT (UUID()),
    campaign_id CHAR(36) NOT NULL REFERENCES campaigns(id),
    store_id CHAR(36) NOT NULL REFERENCES stores(id),
    start_date DATE NOT NULL,
    personality_at_start VARCHAR(20) NOT NULL,
    learning_period_days INT NOT NULL DEFAULT 3,
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_clp_campaign UNIQUE (campaign_id)
);
CALL adpilot_create_index_if_missing('campaign_learning_periods', 'idx_clp_store', '(store_id)');
CALL adpilot_create_index_if_missing('campaign_learning_periods', 'idx_clp_campaign', '(campaign_id)');

-- =====================================================================
-- Rollback (advertising-workspace-rework) — Req 51.8
-- To reverse this migration on a provisioned database, run the statements
-- below (drop in FK-dependency order: children before parents). They are
-- commented out so a normal fresh import does NOT drop the tables.
-- ---------------------------------------------------------------------
-- DROP TABLE IF EXISTS campaign_product_links;
-- DROP TABLE IF EXISTS optimization_goal_migration_exceptions;
-- DROP TABLE IF EXISTS acos_migration_exceptions;
-- DROP TABLE IF EXISTS object_status_migration_exceptions;
-- DROP TABLE IF EXISTS personality_policies;
-- DROP TABLE IF EXISTS operation_outbox;
-- DROP TABLE IF EXISTS operation_pending_changes;
-- DROP TABLE IF EXISTS operations;
-- =====================================================================

DROP PROCEDURE IF EXISTS adpilot_create_index_if_missing;
DROP PROCEDURE IF EXISTS adpilot_add_column_if_missing;


-- =====================================================================
-- ===  DEMO seed data (merged from former db/seed-demo.sql)         ===
-- =====================================================================
-- Optional demo/sample data for exercising features WITHOUT a live
-- Amazon Ads connection. Everything is scoped to the Demo store
-- 00000000-0000-0000-0000-000000000301 (seeded above), so it never
-- touches a real connected store.
--
-- Safe to re-run: fixed-id rows use INSERT IGNORE; the CTE-generated
-- performance_daily / orders rows clear the demo store first. Dates are
-- relative to CURDATE()/NOW() so the data always lands inside the
-- dashboard's recent windows. Requires MySQL 8.0+ (recursive CTE, JSON).
--
-- To run a PRODUCTION import WITHOUT demo data, delete this section
-- (everything below this header) before importing, or truncate the demo
-- store afterward using the CLEANUP block at the very bottom.
-- =====================================================================

-- ── Advertising goal ─────────────────────────────────────────
INSERT IGNORE INTO goals (id, store_id, name, type, status, target_acos, daily_budget, max_cpc, min_bid, max_bid, created_by) VALUES
('0000a000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'Demo · 利润优先目标', 'profit', 'active', 25.000000, 120.0000, 2.5000, 0.3000, 4.0000, '00000000-0000-0000-0000-000000000301');

-- ── Campaigns (3) ────────────────────────────────────────────
INSERT IGNORE INTO campaigns (id, goal_id, store_id, name, type, campaign_type, status, budget, budget_type, daily_budget, targeting_type, state, created_by) VALUES
('0000c000-0000-0000-0000-000000000001', '0000a000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'SP · Walking Pad · 核心词', 'sponsoredProducts', 'sponsoredProducts', 'active', 60.00, 'daily', 60.0000, 'manual', 'enabled', '00000000-0000-0000-0000-000000000301'),
('0000c000-0000-0000-0000-000000000002', '0000a000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'SP · Electric Dirt Bike · 自动', 'sponsoredProducts', 'sponsoredProducts', 'active', 80.00, 'daily', 80.0000, 'auto', 'enabled', '00000000-0000-0000-0000-000000000301'),
('0000c000-0000-0000-0000-000000000003', '0000a000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'SP · Trailer Hitch · 长尾', 'sponsoredProducts', 'sponsoredProducts', 'active', 40.00, 'daily', 40.0000, 'manual', 'enabled', '00000000-0000-0000-0000-000000000301');

-- ── Ad groups (3) ────────────────────────────────────────────
INSERT IGNORE INTO ad_groups (id, campaign_id, store_id, name, default_bid, status) VALUES
('0000a600-0000-0000-0000-000000000001', '0000c000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'Walking Pad 核心词组', 1.2000, 'active'),
('0000a600-0000-0000-0000-000000000002', '0000c000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', 'Electric Bike 自动组', 0.9000, 'active'),
('0000a600-0000-0000-0000-000000000003', '0000c000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', 'Trailer Hitch 长尾组', 0.7000, 'active');

-- ── Keywords (8) — mix of winners / waste / high-acos ────────
INSERT IGNORE INTO keywords (id, campaign_id, ad_group_id, store_id, keyword_text, match_type, bid, status, impressions, clicks, spend, sales, orders, acos, roas, ctr, cvr, avg_cpc) VALUES
('0000be00-0000-0000-0000-000000000001', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'walking pad',            'exact',  1.4000, 'active', 52000, 2600, 1850.00, 12400.00, 410, 0.1492, 6.70, 0.0500, 0.1577, 0.7115),
('0000be00-0000-0000-0000-000000000002', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'under desk treadmill',   'phrase', 1.1000, 'active', 31000, 1240,  980.00,     0.00,   0, 0.0000, 0.00, 0.0400, 0.0000, 0.7903),
('0000be00-0000-0000-0000-000000000003', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'walking pad with incline','exact', 1.6000, 'active',  2100,  168,  240.00,  1980.00,  62, 0.1212, 8.25, 0.0800, 0.3690, 1.4286),
('0000be00-0000-0000-0000-000000000004', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'treadmill desk',         'broad',  0.9000, 'active', 18000,  720, 1080.00,  2040.00,  34, 0.5294, 1.89, 0.0400, 0.0472, 1.5000),
('0000be00-0000-0000-0000-000000000005', '0000c000-0000-0000-0000-000000000002', '0000a600-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', 'electric dirt bike',     'exact',  1.0000, 'active', 24000, 1080, 1620.00, 10800.00, 120, 0.1500, 6.67, 0.0450, 0.1111, 1.5000),
('0000be00-0000-0000-0000-000000000006', '0000c000-0000-0000-0000-000000000002', '0000a600-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', 'kids electric motorcycle','broad', 0.8000, 'active', 14000,  420,  504.00,   900.00,  10, 0.5600, 1.79, 0.0300, 0.0238, 1.2000),
('0000be00-0000-0000-0000-000000000007', '0000c000-0000-0000-0000-000000000003', '0000a600-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', 'trailer hitch lock',     'phrase', 0.7000, 'active',  9800,  392,  235.20,  1764.00,  84, 0.1333, 7.50, 0.0400, 0.2143, 0.6000),
('0000be00-0000-0000-0000-000000000008', '0000c000-0000-0000-0000-000000000003', '0000a600-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', 'tow hitch receiver',     'broad',  0.6000, 'active', 12000,  360,  324.00,   216.00,   3, 1.5000, 0.67, 0.0300, 0.0083, 0.9000);

-- ── Search terms (8) — harvest / negative candidates ─────────
INSERT IGNORE INTO search_terms (id, campaign_id, ad_group_id, keyword_id, store_id, search_term, impressions, clicks, orders, spend, sales, acos, ctr, cvr, cpc, roas, harvested, harvesting_status, period_start, period_end) VALUES
('0000571e-0000-0000-0000-000000000001', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'walking pad for home office', 3200, 192, 28, 210.00, 1680.00, 0.125000, 0.0600, 0.145833, 1.0938, 8.00, 0, 'add_exact',   DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000002', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', 'quiet walking pad',          2100, 126, 22, 138.00, 1320.00, 0.104545, 0.0600, 0.174603, 1.0952, 9.57, 0, 'add_exact',   DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000003', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', 'treadmill for running',      4800, 192,  0, 173.00,    0.00, 0.000000, 0.0400, 0.000000, 0.9010, 0.00, 0, 'add_negative',DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000004', '0000c000-0000-0000-0000-000000000001', '0000a600-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', 'cheap exercise bike',        3600, 144,  0, 129.00,    0.00, 0.000000, 0.0400, 0.000000, 0.8958, 0.00, 0, 'add_negative',DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000005', '0000c000-0000-0000-0000-000000000002', '0000a600-0000-0000-0000-000000000002', '0000be00-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000301', 'electric dirt bike for adults',1800, 90, 14, 117.00, 1120.00, 0.104464, 0.0500, 0.155556, 1.3000, 9.57, 0, 'add_exact',   DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000006', '0000c000-0000-0000-0000-000000000002', '0000a600-0000-0000-0000-000000000002', '0000be00-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000301', 'electric scooter',           5200, 156,  1, 156.00,   90.00, 1.733333, 0.0300, 0.006410, 1.0000, 0.58, 0, 'add_negative',DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000007', '0000c000-0000-0000-0000-000000000003', '0000a600-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000301', 'heavy duty trailer hitch',   1400, 84, 18, 58.80, 1134.00, 0.051852, 0.0600, 0.214286, 0.7000, 19.29, 0, 'add_exact',  DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE()),
('0000571e-0000-0000-0000-000000000008', '0000c000-0000-0000-0000-000000000003', '0000a600-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000301', 'boat trailer parts',         2600, 78,  0, 70.20,    0.00, 0.000000, 0.0300, 0.000000, 0.9000, 0.00, 0, 'watchlist',  DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE());

-- ── Keyword coverage (12) — drives the "运行分析" rules ────────
INSERT IGNORE INTO keyword_coverage (id, product_id, store_id, campaign_id, keyword_id, keyword_text, match_type, in_title, in_bullets, in_description, in_backend_search_terms, is_in_listing, is_in_title, is_in_bullet_points, is_in_backend, impressions, clicks, orders, spend, sales, acos, cvr, coverage_score, coverage_status, recommendation) VALUES
('0000c0e0-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000001', 'walking pad',             'exact',  1,1,1,1, 1,1,1,1, 52000, 2600, 410, 1850.00, 12400.00, 0.1492, 0.1577, 95, 'covered',   '核心优质词，覆盖完整'),
('0000c0e0-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000002', 'under desk treadmill',    'phrase', 1,0,0,1, 1,1,0,1, 31000, 1240,   0,  980.00,     0.00, 0.0000, 0.0000, 40, 'partial',   '高花费零转化，建议否定或降价'),
('0000c0e0-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000003', 'walking pad with incline','exact',  0,0,0,0, 0,0,0,0,  2100,  168,  62,  240.00,  1980.00, 0.1212, 0.3690,  0, 'missing',   '低曝光高转化且 Listing 未覆盖，建议加入标题/亮点'),
('0000c0e0-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000004', 'treadmill desk',          'broad',  1,0,0,0, 1,1,0,0, 18000,  720,  34, 1080.00,  2040.00, 0.5294, 0.0472, 55, 'partial',   'ACoS 偏高，建议降低竞价'),
('0000c0e0-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', NULL,                                  'fitwalk',                 'exact',  1,1,0,1, 1,1,1,1,   800,   60,  20,   30.00,   600.00, 0.0500, 0.3333, 90, 'covered',   '品牌词，保持防御'),
('0000c0e0-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000002', '0000be00-0000-0000-0000-000000000005', 'electric dirt bike',      'exact',  1,1,1,1, 1,1,1,1, 24000, 1080, 120, 1620.00, 10800.00, 0.1500, 0.1111, 92, 'covered',   '核心优质词'),
('0000c0e0-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000002', '0000be00-0000-0000-0000-000000000006', 'kids electric motorcycle','broad',  0,0,0,1, 0,0,0,1, 14000,  420,  10,  504.00,   900.00, 0.5600, 0.0238, 30, 'partial',   'ACoS 过高且未进 Listing，建议优化或否定'),
('0000c0e0-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000002', NULL,                                  'voltride',                'exact',  1,1,0,1, 1,1,1,1,   620,   48,  16,   24.00,   480.00, 0.0500, 0.3333, 88, 'covered',   '品牌词'),
('0000c0e0-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000007', 'trailer hitch lock',      'phrase', 1,1,0,1, 1,1,1,1,  9800,  392,  84,  235.20,  1764.00, 0.1333, 0.2143, 85, 'covered',   '优质长尾词'),
('0000c0e0-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000008', 'tow hitch receiver',      'broad',  0,0,0,0, 1,0,0,0, 12000,  360,   3,  324.00,   216.00, 1.5000, 0.0083, 25, 'partial',   '严重浪费，建议否定'),
('0000c0e0-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000003', NULL,                                  'heavy duty trailer hitch','phrase', 0,0,0,0, 0,0,0,0,  1400,   84,  18,   58.80,  1134.00, 0.0519, 0.2143,  0, 'missing',   '高转化但 Listing 未覆盖，建议收割并写入 Listing'),
('0000c0e0-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000404', '00000000-0000-0000-0000-000000000301', NULL,                                  NULL,                                  'platform bed frame',      'exact',  1,1,1,1, 1,1,1,1,  6400,  256,  48,  192.00,  2400.00, 0.0800, 0.1875, 90, 'covered',   '优质词');

-- ── Keyword insights (6) — pre-populated so the page shows data ──
INSERT IGNORE INTO keyword_insights (id, store_id, product_id, campaign_id, keyword_id, text, source, segment, health_score, opportunity_score, waste_score, confidence_score, recommended_action, reason, current_data, expected_impact, risk_level, status) VALUES
('0000115e-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000401', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000001', 'walking pad',              'keyword',     'winner',         92, 80, 5,  90, 'scale_bid',    'ACoS 14.9%，转化稳定，建议提价扩量', '{"impressions":52000,"clicks":2600,"orders":410,"spend":1850,"sales":12400,"acos":0.1492,"cvr":0.1577,"roas":6.7,"currentBid":1.4,"suggestedBid":1.7,"matchType":"exact","inListing":true}', '预计销售额 +12%', 'low',    'pending'),
('0000115e-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000401', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000002', 'under desk treadmill',     'search_term', 'waste',          20, 5,  85, 88, 'add_negative', '花费 $980 零转化，建议否定', '{"impressions":31000,"clicks":1240,"orders":0,"spend":980,"sales":0,"acos":0,"cvr":0,"matchType":"phrase","inListing":true}', '预计节省 $980/月', 'medium', 'pending'),
('0000115e-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000401', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000003', 'walking pad with incline', 'search_term', 'add_to_exact',   78, 88, 10, 82, 'add_exact',    '低曝光高转化(CVR 36.9%)，建议加入精确匹配并写入 Listing', '{"impressions":2100,"clicks":168,"orders":62,"spend":240,"sales":1980,"acos":0.1212,"cvr":0.369,"matchType":"exact","inListing":false}', '预计新增 60+ 单/月', 'low', 'pending'),
('0000115e-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000401', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000004', 'treadmill desk',           'keyword',     'add_to_negative',45, 20, 60, 75, 'reduce_bid',   'ACoS 52.9% 偏高，建议降价或限词', '{"impressions":18000,"clicks":720,"orders":34,"spend":1080,"sales":2040,"acos":0.5294,"cvr":0.0472,"matchType":"broad","inListing":true}', '预计 ACoS 降至 30%', 'medium', 'pending'),
('0000115e-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000403', '0000c000-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000008', 'tow hitch receiver',       'keyword',     'waste',          15, 5,  90, 86, 'add_negative', 'ACoS 150% 严重浪费，建议立即否定', '{"impressions":12000,"clicks":360,"orders":3,"spend":324,"sales":216,"acos":1.5,"cvr":0.0083,"matchType":"broad","inListing":true}', '预计节省 $300+/月', 'high', 'pending'),
('0000115e-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000403', '0000c000-0000-0000-0000-000000000003', NULL,                                  'heavy duty trailer hitch', 'search_term', 'listing_missing',70, 82, 12, 80, 'add_exact',    '高转化但 Listing 未覆盖，建议收割', '{"impressions":1400,"clicks":84,"orders":18,"spend":58.8,"sales":1134,"acos":0.0519,"cvr":0.2143,"matchType":"phrase","inListing":false}', '预计新增 18 单/月', 'low', 'pending');

-- ── Recommendations (5) — AI 建议页 ──────────────────────────
INSERT IGNORE INTO recommendations (id, store_id, campaign_id, keyword_id, type, priority, title, description, reason, target_entity_type, target_entity_name, current_value, recommended_value, estimated_impact, confidence, current_data, expected_impact, risk_level, status) VALUES
('0000bec0-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000001', 'bid_increase',  'high',   '提高 "walking pad" 竞价以扩量', 'ACoS 14.9% 远低于目标 25%，存在加价扩量空间', 'ACoS 低于目标，转化稳定', 'keyword', 'walking pad', '$1.40', '$1.70', 1500.00, 90.00, '{"acos":0.1492,"orders":410}', '预计销售额 +12%', 'low',    'pending'),
('0000bec0-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000002', 'add_negative',  'high',   '否定 "under desk treadmill"',  '花费 $980 零转化', '零转化高花费', 'keyword', 'under desk treadmill', '启用', '否定', 980.00, 88.00, '{"spend":980,"orders":0}', '预计节省 $980/月', 'medium', 'pending'),
('0000bec0-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000001', '0000be00-0000-0000-0000-000000000004', 'bid_decrease',  'medium', '降低 "treadmill desk" 竞价',   'ACoS 52.9% 高于目标，建议降价', 'ACoS 过高', 'keyword', 'treadmill desk', '$0.90', '$0.60', 600.00, 80.00, '{"acos":0.5294}', '预计 ACoS 降至 ~30%', 'medium', 'pending'),
('0000bec0-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000002', '0000be00-0000-0000-0000-000000000006', 'add_negative',  'medium', '否定 "kids electric motorcycle"','ACoS 56% 持续亏损', 'ACoS 过高', 'keyword', 'kids electric motorcycle', '启用', '否定', 504.00, 78.00, '{"acos":0.56}', '预计节省 $500/月', 'medium', 'pending'),
('0000bec0-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000301', '0000c000-0000-0000-0000-000000000003', '0000be00-0000-0000-0000-000000000008', 'pause_keyword', 'high',   '暂停 "tow hitch receiver"',    'ACoS 150% 严重亏损', '严重浪费', 'keyword', 'tow hitch receiver', '启用', '暂停', 324.00, 86.00, '{"acos":1.5}', '预计节省 $300+/月', 'high', 'pending');

-- ── Operation tasks (5) — 今日待办 ───────────────────────────
INSERT IGNORE INTO operation_tasks (id, store_id, title, description, task_type, source_type, related_entity_type, priority, risk_level, status, suggested_action, expected_impact, created_by, due_date) VALUES
('0000405c-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', '否定高花费零转化词 "under desk treadmill"', '该词 30 天花费 $980 且零转化，建议尽快否定', 'keyword', 'ai', 'keyword', 'urgent', 'medium', 'open', '在广告活动中添加否定精确匹配', '预计节省 $980/月', '00000000-0000-0000-0000-000000000301', NOW()),
('0000405c-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', '暂停严重亏损词 "tow hitch receiver"', 'ACoS 150%，持续亏损', 'ads', 'ai', 'keyword', 'urgent', 'high', 'open', '暂停该关键词', '预计节省 $300+/月', '00000000-0000-0000-0000-000000000301', NOW()),
('0000405c-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', '收割高转化搜索词 "walking pad for home office"', 'CVR 14.6%，建议加入精确匹配', 'keyword', 'rule', 'search_term', 'high', 'low', 'open', '加入精确匹配关键词', '预计新增 28 单/月', '00000000-0000-0000-0000-000000000301', NOW()),
('0000405c-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000301', '补充库存：Vibration Plate 仅剩 15 件', '低库存预警，建议补货', 'inventory', 'system', 'product', 'high', 'medium', 'open', '创建补货计划', '避免断货损失', '00000000-0000-0000-0000-000000000301', DATE_ADD(NOW(), INTERVAL 1 DAY)),
('0000405c-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000301', 'Listing 优化：Trailer Hitch 缺失高转化词', '"heavy duty trailer hitch" 高转化但未写入 Listing', 'listing', 'ai', 'product', 'medium', 'low', 'open', '将关键词写入标题/商品亮点', '预计新增 18 单/月', '00000000-0000-0000-0000-000000000301', DATE_ADD(NOW(), INTERVAL 2 DAY));

-- ── Approval requests (3) — 审批管理 ─────────────────────────
INSERT IGNORE INTO approval_requests (id, store_id, requester_id, request_type, related_entity_type, related_entity_id, title, description, payload, risk_level, status, module, action_type, initiated_by, current_level) VALUES
('0000a99a-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000301', 'bid_change', 'keyword', '0000be00-0000-0000-0000-000000000001', '提高 "walking pad" 竞价 $1.40 → $1.70', 'AI 建议提价扩量，需审批', '{"keyword":"walking pad","oldBid":1.4,"newBid":1.7,"reason":"ACoS 低于目标"}', 'low',    'pending', 'advertising', 'bid_change',    '00000000-0000-0000-0000-000000000301', 1),
('0000a99a-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000301', 'budget_change', 'campaign', '0000c000-0000-0000-0000-000000000002', '提高 "Electric Dirt Bike" 日预算 $80 → $120', '预算频繁打满，建议提额', '{"campaign":"SP · Electric Dirt Bike","oldBudget":80,"newBudget":120}', 'medium', 'pending', 'advertising', 'budget_change', '00000000-0000-0000-0000-000000000301', 1),
('0000a99a-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000301', 'pause_keyword', 'keyword', '0000be00-0000-0000-0000-000000000008', '暂停亏损词 "tow hitch receiver"', 'ACoS 150%，AI 建议暂停', '{"keyword":"tow hitch receiver","acos":1.5,"action":"pause"}', 'high', 'pending', 'advertising', 'pause_keyword', '00000000-0000-0000-0000-000000000301', 1);

-- ── Performance daily (30 days × 3 campaigns) ────────────────
-- Re-runnable: clears demo store rows first (UUID PKs can't dedupe).
DELETE FROM performance_daily WHERE store_id = '00000000-0000-0000-0000-000000000301';
INSERT INTO performance_daily
  (id, store_id, campaign_id, entity_type, entity_id, report_date, impressions, clicks, orders, spend, sales)
WITH RECURSIVE d AS (
  SELECT 0 AS n UNION ALL SELECT n + 1 FROM d WHERE n < 29
)
SELECT UUID(), c.store_id, c.id, 'campaign', c.id,
       DATE_SUB(CURDATE(), INTERVAL d.n DAY),
       800 + (d.n * 37) MOD 500,
       30  + (d.n * 7)  MOD 30,
       3   + (d.n * 3)  MOD 6,
       20  + (d.n * 11) MOD 40,
       150 + (d.n * 53) MOD 400
FROM d
CROSS JOIN (SELECT id, store_id FROM campaigns WHERE store_id = '00000000-0000-0000-0000-000000000301') c;

-- ── Orders (30 days × 3 products) — feeds 总销售额 / 趋势 ─────
DELETE FROM orders WHERE store_id = '00000000-0000-0000-0000-000000000301';
INSERT INTO orders
  (id, store_id, order_id, order_item_id, purchase_date, order_status, fulfillment_channel,
   sales_channel, marketplace_id, sku, asin, product_name, quantity_ordered, item_price,
   shipping_price, item_promotion_discount, currency)
WITH RECURSIVE d AS (
  SELECT 0 AS n UNION ALL SELECT n + 1 FROM d WHERE n < 29
)
SELECT UUID(), p.store_id,
       CONCAT('DEMO-', DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL d.n DAY), '%Y%m%d'), '-', p.sku),
       CONCAT('ITEM-', d.n, '-', p.sku),
       DATE_SUB(NOW(), INTERVAL d.n DAY),
       'Shipped', 'AFN', 'Amazon.com', 'ATVPDKIKX0DER',
       p.sku, p.asin, p.name,
       1 + (d.n MOD 3),
       p.price * (1 + (d.n MOD 3)),
       0.00, 0.00, 'USD'
FROM d
CROSS JOIN (
  SELECT store_id, sku, asin, name, price FROM products
  WHERE store_id = '00000000-0000-0000-0000-000000000301'
    AND sku IN ('WALKPAD-001', 'EBIKE-001', 'HITCH-001')
) p;

-- ============================================================
-- DEMO CLEANUP — run this block to remove ALL demo data later.
-- (Everything is scoped to the demo store; products/users/store
--  seeded above are left intact.)
-- ============================================================
-- DELETE FROM performance_daily      WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM orders                 WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM approval_requests      WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM operation_tasks        WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM recommendations        WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM keyword_insights       WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM keyword_coverage       WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM search_terms           WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM keywords               WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM ad_groups              WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM campaigns              WHERE store_id   = '00000000-0000-0000-0000-000000000301';
-- DELETE FROM goals                  WHERE id         = '0000a000-0000-0000-0000-000000000001';


-- =====================================================================
-- ===  platform-workspace-rbac — Store-Group / Platform-Access ========
-- ===  idempotent seed + backfill migration  (Req 18.2, 18.5, 18.6, 10.7)
-- =====================================================================
-- This block promotes the legacy free-text `stores.store_group` label to the
-- first-class `store_groups` entity, derives `stores.platform_family`, and
-- grants a defined default Platform_Access to accounts that have none.
--
-- It is IDEMPOTENT (Req 18.6): every step is keyed by a unique constraint via
-- INSERT IGNORE, or is an existence-guarded UPDATE (only touches NULL columns),
-- or is guarded by NOT EXISTS. Running it more than once yields identical
-- Store_Group assignments and default dimension grants as running it once.
--
-- It runs AFTER all V1 reference/demo seed data above, so the demo org, demo
-- store, and any imported legacy rows are present before backfill.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Step 1 — Seed per-family default `store_groups` rows (Req 10.7, 18.2).
-- The per-family `is_default = 1` group is the fallback for stores that have
-- no legacy label (or whose label has no matching group). Fixed UUIDs are used
-- for the demo org so its default groups are stable across imports; the
-- generic INSERT IGNORE ... SELECT below covers every other organization.
-- Keyed by uk_store_group_name (org_id, platform_family, name) so re-running
-- inserts nothing the second time.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO store_groups (id, org_id, name, platform_family, is_default) VALUES
('00000000-0000-0000-0000-0000000005a1', '00000000-0000-0000-0000-000000000001', '默认分组', 'amazon',           1),
('00000000-0000-0000-0000-0000000005a2', '00000000-0000-0000-0000-000000000001', '默认分组', 'independent_site', 1);

-- Default amazon group for any organization that lacks one.
INSERT IGNORE INTO store_groups (id, org_id, name, platform_family, is_default)
SELECT UUID(), o.id, '默认分组', 'amazon', 1
FROM organizations o
WHERE NOT EXISTS (
    SELECT 1 FROM store_groups sg
    WHERE sg.org_id = o.id AND sg.platform_family = 'amazon' AND sg.is_default = 1
);

-- Default independent-site group for any organization that lacks one.
INSERT IGNORE INTO store_groups (id, org_id, name, platform_family, is_default)
SELECT UUID(), o.id, '默认分组', 'independent_site', 1
FROM organizations o
WHERE NOT EXISTS (
    SELECT 1 FROM store_groups sg
    WHERE sg.org_id = o.id AND sg.platform_family = 'independent_site' AND sg.is_default = 1
);

-- ---------------------------------------------------------------------
-- Step 2 — Backfill `stores.platform_family` from the store's platform
-- (Req 18.2). Derivation, in priority order: (a) a store that has any Amazon
-- Platform_Connection belongs to the amazon family; (b) otherwise a store that
-- has any non-Amazon Platform_Connection (Shopify / WooCommerce / TikTok /
-- Google) belongs to the independent-site family; (c) every remaining store
-- (the common case of an Amazon marketplace store with no connections yet)
-- defaults to the amazon family. Platform codes are matched case-insensitively
-- with an `amazon%` prefix because connection rows use values such as
-- `amazon_ads`, not a bare `amazon`. Every UPDATE is guarded by
-- `platform_family IS NULL`, so each store is assigned exactly once and
-- re-running is a no-op.
-- ---------------------------------------------------------------------
UPDATE stores s
SET s.platform_family = 'amazon'
WHERE s.platform_family IS NULL
  AND EXISTS (
        SELECT 1 FROM platform_connections pc
        WHERE pc.store_id = s.id AND LOWER(pc.platform) LIKE 'amazon%'
  );

UPDATE stores s
SET s.platform_family = 'independent_site'
WHERE s.platform_family IS NULL
  AND EXISTS (
        SELECT 1 FROM platform_connections pc
        WHERE pc.store_id = s.id
  );

UPDATE stores s
SET s.platform_family = 'amazon'
WHERE s.platform_family IS NULL;

-- ---------------------------------------------------------------------
-- Step 3 — Materialize a `store_groups` row for each distinct legacy
-- `store_group` label, within the store's resolved platform family (Req 18.2).
-- These are ordinary (non-default) groups an administrator can keep using.
-- Keyed by uk_store_group_name so duplicate labels collapse to one group and
-- re-running inserts nothing new.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO store_groups (id, org_id, name, platform_family, is_default)
SELECT UUID(), s.org_id, TRIM(s.store_group), s.platform_family, 0
FROM stores s
WHERE s.store_group IS NOT NULL
  AND TRIM(s.store_group) <> ''
  AND s.platform_family IS NOT NULL
GROUP BY s.org_id, s.platform_family, TRIM(s.store_group);

-- ---------------------------------------------------------------------
-- Step 4 — Backfill `stores.store_group_id` (Req 10.2, 18.2). First match the
-- legacy label to its now-materialized group (same org + platform family);
-- then assign any still-unassigned store to its platform-family default group
-- (Req 10.7). Both UPDATEs are guarded by `store_group_id IS NULL`, so the
-- assignment is performed once and re-running yields the identical mapping.
-- ---------------------------------------------------------------------
UPDATE stores s
JOIN store_groups sg
  ON sg.org_id = s.org_id
 AND sg.platform_family = s.platform_family
 AND sg.name = TRIM(s.store_group)
SET s.store_group_id = sg.id
WHERE s.store_group_id IS NULL
  AND s.store_group IS NOT NULL
  AND TRIM(s.store_group) <> '';

UPDATE stores s
JOIN store_groups sg
  ON sg.org_id = s.org_id
 AND sg.platform_family = s.platform_family
 AND sg.is_default = 1
SET s.store_group_id = sg.id
WHERE s.store_group_id IS NULL
  AND s.platform_family IS NOT NULL;

-- ---------------------------------------------------------------------
-- Step 5 — Grant a defined default Platform_Access to accounts that have no
-- `account_platform_access` rows (Req 18.5), so no existing account loses all
-- navigation until an administrator assigns the dimension explicitly. The
-- defined default is the full set of four Nav_Block families. Guarded by
-- NOT EXISTS (any row for the user) so only accounts with zero rows are
-- seeded; INSERT IGNORE on uk_account_platform makes the grant idempotent and
-- prevents re-adding families an administrator later removes.
-- ---------------------------------------------------------------------
INSERT IGNORE INTO account_platform_access (id, user_id, platform_family)
SELECT UUID(), u.id, fam.platform_family
FROM users u
CROSS JOIN (
    SELECT 'amazon'           AS platform_family
    UNION ALL SELECT 'independent_site'
    UNION ALL SELECT 'logistics'
    UNION ALL SELECT 'finance'
) fam
WHERE NOT EXISTS (
    SELECT 1 FROM account_platform_access a WHERE a.user_id = u.id
);
