-- ============================================
-- V43: 添加 deposit wallet 相关字段到 wallet_accounts 表
-- 用于支持 Polymarket New API user deposit wallet flow (signatureType=3 / POLY_1271)
-- 既有 legacy 帐号 (Magic / Safe, signatureType 1 / 2) 不受影响：
--   wallet_flow_type 预设为 'LEGACY'，其余 deposit_wallet_* 字段为 NULL
-- ============================================

-- 使用存储过程检查并添加字段（如果不存在），与 V17 风格保持一致
DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS add_deposit_wallet_columns_if_not_exists()
BEGIN
    DECLARE wallet_flow_type_exists INT DEFAULT 0;
    DECLARE deposit_wallet_address_exists INT DEFAULT 0;
    DECLARE deposit_wallet_owner_exists INT DEFAULT 0;
    DECLARE deposit_wallet_nonce_exists INT DEFAULT 0;

    SELECT COUNT(*) INTO wallet_flow_type_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'wallet_flow_type';

    SELECT COUNT(*) INTO deposit_wallet_address_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'deposit_wallet_address';

    SELECT COUNT(*) INTO deposit_wallet_owner_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'deposit_wallet_owner';

    SELECT COUNT(*) INTO deposit_wallet_nonce_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'deposit_wallet_nonce';

    IF wallet_flow_type_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN wallet_flow_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY'
            COMMENT '钱包流程：LEGACY=既有 Magic/Safe，DEPOSIT_WALLET=新 API 用户 deposit wallet (POLY_1271)'
            AFTER wallet_type;
    END IF;

    IF deposit_wallet_address_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN deposit_wallet_address VARCHAR(42) NULL
            COMMENT 'Deposit wallet ERC-1967 proxy 地址 (CREATE2 derived)，仅 wallet_flow_type=DEPOSIT_WALLET 时使用'
            AFTER wallet_flow_type;
    END IF;

    IF deposit_wallet_owner_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN deposit_wallet_owner VARCHAR(42) NULL
            COMMENT 'Deposit wallet owner / signer 地址（通常等同 wallet_address），仅 wallet_flow_type=DEPOSIT_WALLET 时使用'
            AFTER deposit_wallet_address;
    END IF;

    IF deposit_wallet_nonce_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN deposit_wallet_nonce BIGINT NOT NULL DEFAULT 0
            COMMENT 'Deposit wallet 最近一次本地缓存的 WALLET batch nonce，0 表示未初始化'
            AFTER deposit_wallet_owner;
    END IF;
END$$

DELIMITER ;

CALL add_deposit_wallet_columns_if_not_exists();
DROP PROCEDURE IF EXISTS add_deposit_wallet_columns_if_not_exists;

-- 为 deposit_wallet_address 添加唯一约束（允许多个 NULL；MySQL 预设行为）
SET @uk_exists = (SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'wallet_accounts'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                  AND CONSTRAINT_NAME = 'uk_wallet_accounts_deposit_wallet_address'
                  LIMIT 1);
SET @sql = IF(@uk_exists IS NULL,
              'ALTER TABLE wallet_accounts ADD UNIQUE KEY uk_wallet_accounts_deposit_wallet_address (deposit_wallet_address)',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 既有所有帐号的 wallet_flow_type 由上方 ADD COLUMN 的 NOT NULL DEFAULT 'LEGACY' 自动回填，
-- 无需额外 UPDATE。
