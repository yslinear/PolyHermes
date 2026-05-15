package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.enums.WalletFlowType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigInteger

/**
 * Polymarket "New API user deposit wallet flow" 设置状态机
 *
 * 与 LEGACY 流程的 [AccountService.checkAccountSetupStatus] / [AccountService.executeSetupStep]
 * 并存，由 [AccountService] 根据 [Account.walletFlowType] 派发。
 *
 * 4 个步骤（参考 https://docs.polymarket.com/trading/deposit-wallets）：
 *   1. deploy   — POST /submit type=WALLET-CREATE，部署 ERC-1967 deposit wallet proxy
 *   2. trading  — 创建 / 派生 CLOB API 凭证（与 LEGACY 步骤 2 完全一致）
 *   3. approve  — POST /submit type=WALLET 内含一批 ERC-20 approve calldata（pUSD → Exchange）
 *                 与 ERC-1155 setApprovalForAll（CTF → Exchange）
 *   4. sync     — 调用 CLOB /balance-allowance/update?signature_type=3 同步余额缓存
 */
@Service
class DepositWalletSetupService(
    private val accountRepository: AccountRepository,
    private val relayClientService: RelayClientService,
    private val apiKeyService: PolymarketApiKeyService,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(DepositWalletSetupService::class.java)

    /** 与 [AccountService.unlimitedAllowance] 保持一致 */
    private val unlimitedAllowance: BigInteger = BigInteger("2").pow(256) - BigInteger.ONE

    /** approval 目标列表：pUSD ERC-20 approve 给 CTF Exchange V2 与 Neg-Risk CTF Exchange */
    private val pUsdApprovalSpenders: List<String> = listOf(
        PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS,
        PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS
    )

    /** Conditional Tokens 的 ERC-1155 setApprovalForAll 也要分别批准两个 Exchange */
    private val conditionalTokensOperators: List<String> = listOf(
        PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS,
        PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS
    )

    /**
     * Step 1：部署 deposit wallet
     *
     * 若 account.depositWalletAddress 已存在则直接回传成功（idempotent）。
     * 否则透过 Builder Relayer 提交 WALLET-CREATE：
     *   1. 拿到 transactionHash 后，调用 [RelayClientService.parseDepositWalletAddressFromReceipt]
     *      从 `WalletDeployed(address indexed wallet, address indexed owner)` event 解析地址
     *   2. 若 receipt 解析失败（log 缺失 / 轮询超时），回退到 CREATE2 决定性推导
     *      [RelayClientService.deriveDepositWalletAddress]
     *   3. 最终把 depositWalletAddress / depositWalletOwner 写回 Account
     */
    suspend fun deployDepositWallet(account: Account): Result<DeployResult> {
        require(WalletFlowType.fromStringOrDefault(account.walletFlowType) == WalletFlowType.DEPOSIT_WALLET) {
            "Account ${account.id} 不是 DEPOSIT_WALLET 流程"
        }

        // Idempotent：已经部署过则跳过
        account.depositWalletAddress?.takeIf { it.isNotBlank() }?.let { existing ->
            logger.info("Account ${account.id} 已有 deposit_wallet_address=$existing，跳过 WALLET-CREATE")
            return Result.success(DeployResult(depositWalletAddress = existing, transactionHash = null, alreadyDeployed = true))
        }

        val ownerAddress = account.depositWalletOwner ?: account.walletAddress
        val deployResult = relayClientService.deployDepositWalletViaBuilderRelayer(ownerAddress)
        if (deployResult.isFailure) {
            return Result.failure(deployResult.exceptionOrNull()!!)
        }
        val deployment = deployResult.getOrNull()!!
        val txHash = deployment.transactionHash

        // 优先级：deployment 返回 > receipt event 解析 > CREATE2 决定性推导
        var resolvedAddress = deployment.depositWalletAddress
        if (resolvedAddress.isNullOrBlank() && !txHash.isNullOrBlank()) {
            logger.info("尝试从 WALLET-CREATE receipt 解析 WalletDeployed event: tx=$txHash")
            resolvedAddress = relayClientService.parseDepositWalletAddressFromReceipt(txHash, ownerAddress)
        }
        if (resolvedAddress.isNullOrBlank()) {
            // Fallback：用 CREATE2 决定性推导（纯函数推导，不依赖链上状态）
            logger.warn(
                "Receipt 解析未取得 deposit wallet 地址，回退到 CREATE2 推导: accountId=${account.id}, owner=$ownerAddress"
            )
            resolvedAddress = try {
                relayClientService.deriveDepositWalletAddress(ownerAddress)
            } catch (e: Exception) {
                logger.error("CREATE2 推导 deposit wallet 地址失败: ${e.message}", e)
                null
            }
        }

        if (resolvedAddress.isNullOrBlank()) {
            // 三段 fallback（deployment 返回 / receipt event / CREATE2 推导）皆失败，
            // 视为 step 1 失败：不可回报 success，否则会让前端误以为已完成、
            // 进而触发 step 3 在尚无 depositWalletAddress 的状况下连环失败；
            // 也会破坏 idempotent 检查（再次触发将 double-deploy）。
            logger.error(
                "Deposit wallet 部署后无法解析钱包地址: accountId=${account.id}, tx=$txHash, state=${deployment.state}"
            )
            return Result.failure(
                IllegalStateException(
                    "Deposit wallet deployed (tx=$txHash, state=${deployment.state}) 但无法解析钱包地址；" +
                        "请检查 relayer / RPC 状态后重试"
                )
            )
        }

        val updated = account.copy(
            depositWalletAddress = resolvedAddress,
            depositWalletOwner = ownerAddress,
            updatedAt = System.currentTimeMillis()
        )
        accountRepository.save(updated)
        logger.info("Deposit wallet 部署完成: accountId=${account.id}, depositWallet=$resolvedAddress, tx=$txHash")
        return Result.success(DeployResult(
            depositWalletAddress = resolvedAddress,
            transactionHash = txHash,
            alreadyDeployed = false
        ))
    }

    /**
     * Step 3：从 deposit wallet 发出代币授权
     *
     * 提交一个 WALLET batch，包含：
     *   - pUSD.approve(CTF Exchange V2, MAX)
     *   - pUSD.approve(Neg-Risk CTF Exchange, MAX)
     *   - CTF.setApprovalForAll(CTF Exchange V2, true)
     *   - CTF.setApprovalForAll(Neg-Risk CTF Exchange, true)
     */
    suspend fun approveTokens(account: Account): Result<String> {
        require(WalletFlowType.fromStringOrDefault(account.walletFlowType) == WalletFlowType.DEPOSIT_WALLET) {
            "Account ${account.id} 不是 DEPOSIT_WALLET 流程"
        }
        val depositWalletAddress = account.depositWalletAddress
            ?: return Result.failure(IllegalStateException("尚未部署 deposit wallet（depositWalletAddress 为空）"))

        val ownerAddress = account.depositWalletOwner ?: account.walletAddress
        val privateKey = cryptoUtils.decrypt(account.privateKey)

        val approveErc20Selector = "0x095ea7b3"  // approve(address,uint256)
        val setApprovalForAllSelector = "0xa22cb465"  // setApprovalForAll(address,bool)

        val calls = mutableListOf<BuilderRelayerApi.DepositWalletCall>()

        for (spender in pUsdApprovalSpenders) {
            val data = "0x" + approveErc20Selector.removePrefix("0x") +
                EthereumUtils.encodeAddress(spender) +
                EthereumUtils.encodeUint256(unlimitedAllowance)
            calls += BuilderRelayerApi.DepositWalletCall(
                target = PolymarketConstants.PUSD_ADDRESS,
                value = "0",
                data = data
            )
        }
        for (operator in conditionalTokensOperators) {
            // bool true → 0x..01 in last byte
            val data = "0x" + setApprovalForAllSelector.removePrefix("0x") +
                EthereumUtils.encodeAddress(operator) +
                EthereumUtils.encodeUint256(BigInteger.ONE)
            calls += BuilderRelayerApi.DepositWalletCall(
                target = PolymarketConstants.CONDITIONAL_TOKENS_ADDRESS,
                value = "0",
                data = data
            )
        }

        val executeResult = relayClientService.executeViaBuilderRelayerDepositWallet(
            privateKey = privateKey,
            ownerAddress = ownerAddress,
            depositWalletAddress = depositWalletAddress,
            calls = calls
        )

        // 成功后持久化 step 3 完成标记，避免 setup status 永远显示 false、
        // 也避免被前端重复触发授权 batch。
        if (executeResult.isSuccess) {
            val fresh = accountRepository.findById(account.id!!).orElse(account)
            accountRepository.save(
                fresh.copy(
                    depositWalletTokensApproved = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        return executeResult
    }

    /**
     * Step 4：同步 CLOB balance / allowance 缓存（signature_type=3）
     *
     * 透过 PolymarketClobApi 的 L2 auth 调用：
     *   GET /balance-allowance/update?asset_type=COLLATERAL&signature_type=3
     *
     * 调用时机：在 deposit wallet 入金（pUSD）或 approve 完成后，
     * 让 CLOB 重新扫链刷新缓存。若不调用，CLOB 端可能仍使用旧缓存导致下单被拒。
     *
     * 文档：https://docs.polymarket.com/trading/deposit-wallets
     */
    suspend fun syncClobBalance(account: Account): Result<Unit> {
        require(WalletFlowType.fromStringOrDefault(account.walletFlowType) == WalletFlowType.DEPOSIT_WALLET) {
            "Account ${account.id} 不是 DEPOSIT_WALLET 流程"
        }

        // 验证 CLOB 凭证齐备（step 2 应已完成）
        val apiKey = account.apiKey
            ?: return Result.failure(IllegalStateException("CLOB apiKey 为空，请先完成 step 2"))
        val encryptedSecret = account.apiSecret
            ?: return Result.failure(IllegalStateException("CLOB apiSecret 为空，请先完成 step 2"))
        val encryptedPassphrase = account.apiPassphrase
            ?: return Result.failure(IllegalStateException("CLOB apiPassphrase 为空，请先完成 step 2"))

        return try {
            // 解密 CLOB API secret / passphrase（数据库中以加密形式存储）
            val apiSecret = cryptoUtils.decrypt(encryptedSecret)
            val apiPassphrase = cryptoUtils.decrypt(encryptedPassphrase)

            // L2 认证用 owner EOA 地址（POLY_ADDRESS 头），signature_type=3 才告诉 CLOB
            // 实际操作主体是 deposit wallet
            val clobApi = retrofitFactory.createClobApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = account.walletAddress
            )

            val response = clobApi.updateBalanceAllowance(
                assetType = "COLLATERAL",
                signatureType = 3
            )

            if (response.isSuccessful) {
                logger.info("CLOB balance-allowance 同步成功: accountId=${account.id}, depositWallet=${account.depositWalletAddress}")
                // 持久化 step 4 完成标记，让 setup status 可如实回报，
                // 不再需要前端反复触发或额外的 live CLOB 反查。
                val fresh = accountRepository.findById(account.id!!).orElse(account)
                accountRepository.save(
                    fresh.copy(
                        depositWalletBalanceSynced = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                Result.success(Unit)
            } else {
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()
                val msg = "CLOB balance sync failed: ${response.code()} $errorBody"
                logger.warn("$msg, accountId=${account.id}")
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            logger.error("CLOB balance-allowance 同步异常: accountId=${account.id}, ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 检查 deposit wallet 是否已部署（透过 relayer GET /deployed?address=<depositWallet>）
     *
     * 直接复用 [RelayClientService.isDepositWalletDeployed]（包装 BuilderRelayerApi.getDeployed）。
     *
     * @param depositWalletAddress 已知的 deposit wallet 地址；若为空返回 false（视为尚未部署）
     */
    suspend fun isDepositWalletDeployed(depositWalletAddress: String?): Boolean {
        if (depositWalletAddress.isNullOrBlank()) return false
        return relayClientService.isDepositWalletDeployed(depositWalletAddress)
    }

    /**
     * 部署结果
     */
    data class DeployResult(
        val depositWalletAddress: String?,
        val transactionHash: String?,
        val alreadyDeployed: Boolean
    )
}
