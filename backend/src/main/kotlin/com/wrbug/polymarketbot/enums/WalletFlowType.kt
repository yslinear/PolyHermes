package com.wrbug.polymarketbot.enums

/**
 * 钱包流程类型
 *
 * 区分既有 legacy 流程（Magic / Safe，signatureType 1 / 2）与新的
 * Polymarket "New API user deposit wallet flow"（signatureType 3 / POLY_1271）。
 *
 * 注意：本 enum 与 [WalletType] 是正交概念：
 *   - LEGACY 帐号仍需要选 walletType 为 MAGIC 或 SAFE
 *   - DEPOSIT_WALLET 帐号的 walletType 字段在新流程下无意义（统一 owner 推导 deposit wallet）
 *
 * 参考: https://docs.polymarket.com/trading/deposit-wallets
 */
enum class WalletFlowType(val value: String, val description: String) {
    /**
     * 既有流程：Magic（POLY_PROXY, signatureType=1）或 Safe（GNOSIS_SAFE, signatureType=2）
     * 资金存放在 proxy_address，下单时 maker = proxyAddress。
     */
    LEGACY("LEGACY", "既有 Magic / Safe 代理钱包"),

    /**
     * 新流程：Deposit Wallet（ERC-1967 proxy，POLY_1271, signatureType=3）
     * 资金存放在 deposit_wallet_address，下单时 maker = signer = depositWalletAddress；
     * 签名为 ERC-7739 包覆，由 deposit wallet 透过 ERC-1271 验章。
     */
    DEPOSIT_WALLET("DEPOSIT_WALLET", "Deposit Wallet (POLY_1271)");

    companion object {
        /**
         * 从字符串值解析（不区分大小写），未匹配返回 LEGACY。
         * 用于读取 DB / API 入参时容错。
         */
        fun fromStringOrDefault(value: String?, default: WalletFlowType = LEGACY): WalletFlowType {
            if (value.isNullOrBlank()) return default
            return values().find { it.value.equals(value, ignoreCase = true) } ?: default
        }

        fun isValid(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return values().any { it.value.equals(value, ignoreCase = true) }
        }
    }
}
