package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 账户信息实体
 * 用于存储钱包账户信息（支持多账户）
 */
@Entity
@Table(name = "wallet_accounts")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "private_key", nullable = false, length = 500)
    val privateKey: String,  // 私钥（AES 加密存储）
    
    @Column(name = "wallet_address", nullable = false, length = 42)
    val walletAddress: String,  // 钱包地址（从私钥推导），同一 EOA 可有多个账户（不同代理类型）
    
    @Column(name = "proxy_address", unique = true, nullable = false, length = 42)
    val proxyAddress: String,  // Polymarket 代理钱包地址（从合约获取，必须），唯一
    
    @Column(name = "api_key", length = 500)
    val apiKey: String? = null,  // Polymarket API Key（可选，明文存储）
    
    @Column(name = "api_secret", length = 500)
    val apiSecret: String? = null,  // Polymarket API Secret（可选，AES 加密存储）
    
    @Column(name = "api_passphrase", length = 500)
    val apiPassphrase: String? = null,  // Polymarket API Passphrase（可选，AES 加密存储）
    
    @Column(name = "account_name", length = 100)
    val accountName: String? = null,
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,  // 是否默认账户
    
    @Column(name = "is_enabled", nullable = false)
    val isEnabled: Boolean = true,  // 是否启用（用于订单推送等功能的开关）
    
    @Column(name = "wallet_type", nullable = false, length = 20)
    val walletType: String = "magic",  // 钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）

    @Column(name = "wallet_flow_type", nullable = false, length = 32)
    val walletFlowType: String = "LEGACY",  // 钱包流程：LEGACY 或 DEPOSIT_WALLET（POLY_1271）

    @Column(name = "deposit_wallet_address", length = 42)
    val depositWalletAddress: String? = null,  // Deposit wallet ERC-1967 proxy 地址，仅 DEPOSIT_WALLET 流程使用

    @Column(name = "deposit_wallet_owner", length = 42)
    val depositWalletOwner: String? = null,   // Deposit wallet owner / signer 地址（通常等同 walletAddress）

    @Column(name = "deposit_wallet_nonce", nullable = false)
    val depositWalletNonce: Long = 0L,        // 本地缓存的 WALLET batch nonce

    @Column(name = "deposit_wallet_tokens_approved", nullable = false)
    val depositWalletTokensApproved: Boolean = false,  // DEPOSIT_WALLET 流程 step 3 完成标记

    @Column(name = "deposit_wallet_balance_synced", nullable = false)
    val depositWalletBalanceSynced: Boolean = false,   // DEPOSIT_WALLET 流程 step 4 完成标记

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

