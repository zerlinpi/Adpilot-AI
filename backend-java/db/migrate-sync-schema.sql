-- =====================================================================
-- AdPilot — Idempotent schema catch-up migration (MySQL 8.0)
-- =====================================================================
-- PURPOSE
--   Bring an EXISTING production database (provisioned from an older version
--   of db/schema.sql) up to date with columns that were added later, WITHOUT
--   dropping or altering any data.
--
--   The application runs no migrations at startup (Flyway disabled, Hibernate
--   ddl-auto = none) and db/schema.sql uses `CREATE TABLE IF NOT EXISTS`, so
--   re-importing schema.sql on a live DB adds any missing *tables* but NEVER
--   adds *columns* to tables that already exist. That gap is what produces
--   runtime errors like:
--       POST /api/auth/login -> 500  ("数据库缺少必要的字段…")
--   because the `users` entity maps columns the older DB does not have.
--
-- WHY A STORED PROCEDURE
--   MySQL 8.0 has no `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. The helper
--   procedure below checks information_schema first, so every statement is
--   idempotent and the whole script is safe to run repeatedly.
--
-- HOW TO RUN
--   mysql -u <user> -p <database> < db/migrate-sync-schema.sql
--   (Take a backup first: mysqldump ... > backup.sql)
-- =====================================================================

SET NAMES utf8mb4;

DELIMITER $$

-- Add a column only when it is absent from the current schema/table.
DROP PROCEDURE IF EXISTS adpilot_add_column_if_missing $$
CREATE PROCEDURE adpilot_add_column_if_missing(
    IN p_table   VARCHAR(64),
    IN p_column  VARCHAR(64),
    IN p_ddl     VARCHAR(1024)  -- column definition, e.g. "INT NOT NULL DEFAULT 0"
)
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    SELECT COUNT(*) INTO v_exists
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table
       AND COLUMN_NAME = p_column;
    IF v_exists = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END $$

DELIMITER ;

-- ---------------------------------------------------------------------
-- users — login-critical columns (V4 default store + V9 account security)
-- These are the columns whose absence breaks POST /api/auth/login.
-- ---------------------------------------------------------------------
CALL adpilot_add_column_if_missing('users', 'default_store_id',   'CHAR(36) NULL');
CALL adpilot_add_column_if_missing('users', 'failed_login_count', 'INT NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('users', 'locked_until',       'DATETIME(3) NULL');
CALL adpilot_add_column_if_missing('users', 'twofa_enabled',      'TINYINT(1) NOT NULL DEFAULT 0');
CALL adpilot_add_column_if_missing('users', 'twofa_secret',       'VARCHAR(255) NULL');
CALL adpilot_add_column_if_missing('users', 'created_by',         'CHAR(36) NULL');

-- Clean up the helper so it does not linger in the schema.
DROP PROCEDURE IF EXISTS adpilot_add_column_if_missing;
