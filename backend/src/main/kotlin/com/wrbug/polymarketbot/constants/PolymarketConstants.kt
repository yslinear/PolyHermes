package com.wrbug.polymarketbot.constants

/**
 * Polymarket API 常量
 * 集中管理所有 Polymarket API 的 URL 配置
 */
object PolymarketConstants {
    
    /**
     * Polymarket CLOB API 基础 URL
     */
    const val CLOB_BASE_URL = "https://clob.polymarket.com"
    
    /**
     * Polymarket RTDS WebSocket URL
     * 用于订单推送服务
     */
    const val RTDS_WS_URL = "wss://ws-subscriptions-clob.polymarket.com"
    
    /**
     * Polymarket User Channel WebSocket URL
     * 用于跟单服务（订阅 Leader 交易）
     */
    const val USER_WS_URL = "wss://ws-live-data.polymarket.com"
    
    /**
     * Polymarket Activity WebSocket URL
     * 用于 Activity 全局交易流监听
     */
    const val ACTIVITY_WS_URL = "wss://ws-live-data.polymarket.com"
    
    /**
     * Polymarket Data API 基础 URL
     */
    const val DATA_API_BASE_URL = "https://data-api.polymarket.com"
    
    /**
     * Polymarket Gamma API 基础 URL
     */
    const val GAMMA_BASE_URL = "https://gamma-api.polymarket.com"
    
    /**
     * Builder Relayer API URL
     * 用于 Gasless 交易
     */
    const val BUILDER_RELAYER_URL = "https://relayer-v2.polymarket.com/"

    /**
     * Polymarket Safe 代理工厂合约地址（Polygon 主网）
     * 用于 Safe 类型账户的代理部署（SAFE-CREATE）
     */
    const val SAFE_PROXY_FACTORY_ADDRESS = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"

    /** SafeCreate 用 EIP-712 domain name，与 builder-relayer-client 一致 */
    const val SAFE_FACTORY_EIP712_NAME = "Polymarket Contract Proxy Factory"

    /**
     * Polymarket Deposit Wallet Factory 合约地址（Polygon 主网）
     * 用于 New API user 流程的 ERC-1967 proxy 部署（WALLET-CREATE / WALLET）
     * 参考: https://docs.polymarket.com/resources/contracts
     */
    const val DEPOSIT_WALLET_FACTORY_ADDRESS = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07"

    /**
     * Polymarket Deposit Wallet Implementation 合约地址（Polygon 主网）
     * 用于 CREATE2 推导 deposit wallet ERC-1967 proxy 地址（Solady LibClone）
     * 参考: https://github.com/Polymarket/py-builder-relayer-client/blob/main/py_builder_relayer_client/config.py
     *      CONFIG[137].deposit_wallet_implementation
     */
    const val DEPOSIT_WALLET_IMPLEMENTATION_ADDRESS = "0x58CA52ebe0DadfdF531Cde7062e76746de4Db1eB"

    /** Deposit wallet Batch EIP-712 domain name */
    const val DEPOSIT_WALLET_EIP712_NAME = "DepositWallet"

    /** Deposit wallet Batch EIP-712 domain version */
    const val DEPOSIT_WALLET_EIP712_VERSION = "1"

    /**
     * pUSD (Polymarket USD) ERC-20 代理合约地址（Polygon 主网）
     * 新流程下所有 deposit wallet 的抵押币
     */
    const val PUSD_ADDRESS = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"

    /** CTF Exchange V2 合约地址（Polygon 主网） */
    const val CTF_EXCHANGE_V2_ADDRESS = "0xE111180000d2663C0091e4f400237545B87B996B"

    /** Neg-Risk CTF Exchange V2 合约地址（Polygon 主网） */
    const val NEG_RISK_CTF_EXCHANGE_V2_ADDRESS = "0xe2222d279d744050d28e00520010520000310F59"

    /** Conditional Tokens (CTF) 合约地址 */
    const val CONDITIONAL_TOKENS_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"

    /** Polygon mainnet chain id */
    const val POLYGON_CHAIN_ID: Long = 137L
}

