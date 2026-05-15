package com.wrbug.polymarketbot.dto

/**
 * 账户设置状态检查结果
 */
data class AccountSetupStatusDto(
    /**
     * 步骤1：代理钱包是否已部署（LEGACY 流程）
     * 在 DEPOSIT_WALLET 流程下此字段含义改为「deposit wallet 是否已部署」
     */
    val proxyDeployed: Boolean,

    /**
     * 步骤2：交易是否已启用（API Key 是否已配置）
     * LEGACY 与 DEPOSIT_WALLET 两种流程共用此字段
     */
    val tradingEnabled: Boolean,

    /**
     * 步骤3：代币是否已批准
     * LEGACY 流程：USDC 对各 Exchange 的 allowance
     * DEPOSIT_WALLET 流程：pUSD + Conditional Tokens 对各 Exchange 的 allowance
     */
    val tokensApproved: Boolean,

    /**
     * 代币批准详情（各合约的授权额度）
     * Key: 合约名称（CTF_CONTRACT, CTF_EXCHANGE, NEG_RISK_EXCHANGE, NEG_RISK_ADAPTER）
     * Value: 授权额度（USDC / pUSD，6 位小数）
     */
    val approvalDetails: Map<String, String>? = null,

    /**
     * 钱包流程：LEGACY 或 DEPOSIT_WALLET
     * 前端依此值决定显示 3-step (legacy) 或 4-step (deposit wallet) UI
     */
    val walletFlowType: String = "LEGACY",

    /**
     * Deposit wallet 地址（仅 DEPOSIT_WALLET 流程使用，未部署前为 null）
     */
    val depositWalletAddress: String? = null,

    /**
     * DEPOSIT_WALLET 流程步骤 4：CLOB 余额缓存是否已同步
     */
    val balanceSynced: Boolean = false,

    /**
     * 检查错误信息（如果有）
     */
    val error: String? = null
)

/**
 * 执行设置步骤请求
 */
data class ExecuteSetupStepRequest(
    /** 账户 ID */
    val accountId: Long? = null,
    /**
     * 步骤编号，含义依 wallet_flow_type 而定：
     *   LEGACY:          1=部署代理, 2=启用交易, 3=批准代币
     *   DEPOSIT_WALLET:  1=部署 deposit wallet, 2=启用交易, 3=授权代币（pUSD/CTF）, 4=同步 CLOB 余额
     */
    val step: Int? = null
)

/**
 * 执行设置步骤响应
 */
data class ExecuteSetupStepResponse(
    /** 是否由后端执行成功（步骤1 仅返回跳转链接，为 false） */
    val success: Boolean = false,
    /** 需跳转时由后端提供的 URL（步骤1 使用） */
    val redirectUrl: String? = null,
    /** 链上交易哈希（步骤3 批准代币成功时返回） */
    val transactionHash: String? = null
)

/**
 * 账户导入响应（扩展，包含设置状态）
 */
data class AccountImportResponse(
    val account: AccountDto,
    val setupStatus: AccountSetupStatusDto? = null  // 设置状态检查结果（可选）
)
