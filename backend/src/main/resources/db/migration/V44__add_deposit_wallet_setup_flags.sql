-- ============================================
-- V44: 为 wallet_accounts 增加 DEPOSIT_WALLET 流程的 setup 状态持久化字段
--   - deposit_wallet_tokens_approved：step 3 (approveTokens) 是否已完成
--   - deposit_wallet_balance_synced ：step 4 (syncClobBalance) 是否已完成
-- 既有 LEGACY 帐号默认 0，不影响原有流程。
-- ============================================

DELIMITER $$
CREATE PROCEDURE IF NOT EXISTS add_deposit_wallet_setup_flags_if_not_exists()
BEGIN
    DECLARE tokens_approved_exists INT DEFAULT 0;
    DECLARE balance_synced_exists INT DEFAULT 0;
    SELECT COUNT(*) INTO tokens_approved_exists FROM INFORMATION_SCHEMA.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'deposit_wallet_tokens_approved';
    SELECT COUNT(*) INTO balance_synced_exists FROM INFORMATION_SCHEMA.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wallet_accounts' AND COLUMN_NAME = 'deposit_wallet_balance_synced';
    IF tokens_approved_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN deposit_wallet_tokens_approved TINYINT(1) NOT NULL DEFAULT 0
            COMMENT 'DEPOSIT_WALLET 流程 step 3 完成标记' AFTER deposit_wallet_nonce;
    END IF;
    IF balance_synced_exists = 0 THEN
        ALTER TABLE wallet_accounts
        ADD COLUMN deposit_wallet_balance_synced TINYINT(1) NOT NULL DEFAULT 0
            COMMENT 'DEPOSIT_WALLET 流程 step 4 完成标记' AFTER deposit_wallet_tokens_approved;
    END IF;
END$$
DELIMITER ;
CALL add_deposit_wallet_setup_flags_if_not_exists();
DROP PROCEDURE IF EXISTS add_deposit_wallet_setup_flags_if_not_exists;
