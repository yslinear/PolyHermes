package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.api.SignedOrderObject
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicLong

/**
 * 订单签名服务
 * 用于创建和签名 Polymarket CLOB API 订单
 * 
 * 参考:
 * - clob-client/src/order-builder/helpers.ts
 * - @polymarket/order-utils 的 ExchangeOrderBuilder
 */
@Service
class OrderSigningService {

    private val logger = LoggerFactory.getLogger(OrderSigningService::class.java)

    /**
     * 根据是否为 Neg Risk 市场返回签约用 exchange 合约地址
     * @param negRisk true 时使用 Neg Risk CTF Exchange，否则使用标准 CTF Exchange
     */
    fun getExchangeContract(negRisk: Boolean): String {
        return if (negRisk) NEG_RISK_EXCHANGE_CONTRACT else EXCHANGE_CONTRACT
    }

    /**
     * 根据钱包类型返回 CLOB 订单签名类型
     * @param walletType Magic=邮箱/社交登录, Safe=Web3 钱包
     * @return 1=POLY_PROXY(Magic), 2=POLY_GNOSIS_SAFE(Safe), 默认 2
     */
    fun getSignatureTypeForWalletType(walletType: String?): Int {
        val walletTypeEnum = com.wrbug.polymarketbot.enums.WalletType.fromStringOrDefault(walletType, com.wrbug.polymarketbot.enums.WalletType.SAFE)
        return if (walletTypeEnum == com.wrbug.polymarketbot.enums.WalletType.MAGIC) 1 else 2
    }

    /**
     * 根据钱包流程 + 钱包类型返回签名类型
     * - DEPOSIT_WALLET 流程：固定回传 3 (POLY_1271)
     * - 其他流程：委派给 [getSignatureTypeForWalletType]
     */
    fun getSignatureTypeForWalletFlow(walletFlowType: String?, walletType: String?): Int {
        val flow = com.wrbug.polymarketbot.enums.WalletFlowType.fromStringOrDefault(walletFlowType)
        return when (flow) {
            com.wrbug.polymarketbot.enums.WalletFlowType.DEPOSIT_WALLET -> 3
            com.wrbug.polymarketbot.enums.WalletFlowType.LEGACY -> getSignatureTypeForWalletType(walletType)
        }
    }

    // V2 合约地址
    private val EXCHANGE_CONTRACT = "0xE111180000d2663C0091e4f400237545B87B996B"
    private val NEG_RISK_EXCHANGE_CONTRACT = "0xe2222d279d744050d28e00520010520000310F59"
    private val CHAIN_ID = 137L
    
    // USDC 有 6 位小数
    private val COLLATERAL_TOKEN_DECIMALS = 6
    
    // 默认 tickSize 配置（0.01，对应 2 位小数）
    private val DEFAULT_TICK_SIZE = "0.01"
    private val DEFAULT_ROUND_CONFIG = RoundConfig(
        price = 2,
        size = 2,
        amount = 4
    )

    // 价格有效范围（Polymarket API 要求）
    private val MIN_PRICE = BigDecimal("0.01")
    private val MAX_PRICE = BigDecimal("0.99")

    /**
     * 订单金额计算结果
     */
    data class OrderAmounts(
        val makerAmount: String,  // 以 wei 为单位（6 位小数）
        val takerAmount: String   // 以 wei 为单位（6 位小数）
    )
    
    /**
     * 舍入配置
     */
    data class RoundConfig(
        val price: Int,   // 价格小数位数
        val size: Int,    // 数量小数位数
        val amount: Int   // 金额小数位数
    )
    
    /**
     * 计算订单金额（makerAmount 和 takerAmount）
     *
     * 参考 clob-client/src/order-builder/helpers.ts 的 getOrderRawAmounts 函数
     *
     * @param side BUY 或 SELL
     * @param size 数量（shares）
     * @param price 价格（0-1 之间）
     * @param roundConfig 舍入配置
     * @return 订单金额
     */
    fun calculateOrderAmounts(
        side: String,
        size: String,
        price: String,
        roundConfig: RoundConfig = DEFAULT_ROUND_CONFIG
    ): OrderAmounts {
        val sizeDecimal = size.toSafeBigDecimal()
        val priceDecimal = price.toSafeBigDecimal()

        // 对价格进行 roundNormal 处理（与 clob-client 保持一致）
        var rawPrice = roundNormal(priceDecimal, roundConfig.price)

        // 验证价格范围，如果超出则调整到最接近的有效值
        // Polymarket API 要求: 0.01 <= price <= 0.99
        if (rawPrice > MAX_PRICE) {
            logger.warn("价格超出最大限制，已调整: $priceDecimal -> $MAX_PRICE")
            rawPrice = MAX_PRICE
        } else if (rawPrice < MIN_PRICE) {
            logger.warn("价格低于最小限制，已调整: $priceDecimal -> $MIN_PRICE")
            rawPrice = MIN_PRICE
        }

        if (side.uppercase() == "BUY") {
            // BUY: makerAmount = price * size (USDC), takerAmount = size (shares)
            // 参考 clob-client/src/order-builder/helpers.ts 第 73-89 行
            // 注意：Polymarket API 要求市场买入订单的 makerAmount 最多 2 位小数，takerAmount 最多 4 位小数
            // takerAmount (shares) 使用 4 位小数
            val rawTakerAmt = roundDown(sizeDecimal, 4)

            var rawMakerAmt = rawTakerAmt.multiply(rawPrice)
            // makerAmount (USDC) 使用 2 位小数
            if (decimalPlaces(rawMakerAmt) > 2) {
                rawMakerAmt = roundUp(rawMakerAmt, 2 + 4)
                if (decimalPlaces(rawMakerAmt) > 2) {
                    rawMakerAmt = roundDown(rawMakerAmt, 2)
                }
            }

            // 转换为 wei（6 位小数）
            val makerAmount = parseUnits(rawMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(rawTakerAmt, COLLATERAL_TOKEN_DECIMALS)

            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        } else {
            // SELL: makerAmount = size (shares), takerAmount = price * size (USDC)
            // 参考 clob-client/src/order-builder/helpers.ts 第 90-105 行
            val rawMakerAmt = roundDown(sizeDecimal, roundConfig.size)

            var rawTakerAmt = rawMakerAmt.multiply(rawPrice)
            // 如果 takerAmount 的小数位数超过 roundConfig.amount，进行特殊舍入处理
            if (decimalPlaces(rawTakerAmt) > roundConfig.amount) {
                rawTakerAmt = roundUp(rawTakerAmt, roundConfig.amount + 4)
                if (decimalPlaces(rawTakerAmt) > roundConfig.amount) {
                    rawTakerAmt = roundDown(rawTakerAmt, roundConfig.amount)
                }
            }

            // 转换为 wei（6 位小数）
            val makerAmount = parseUnits(rawMakerAmt, COLLATERAL_TOKEN_DECIMALS)
            val takerAmount = parseUnits(rawTakerAmt, COLLATERAL_TOKEN_DECIMALS)

            return OrderAmounts(makerAmount.toString(), takerAmount.toString())
        }
    }
    
    /**
     * 创建并签名订单 (V2)
     *
     * @param privateKey 私钥（十六进制字符串）
     * @param makerAddress maker 地址（funder，通常是 proxyAddress）
     * @param tokenId token ID
     * @param side BUY 或 SELL
     * @param price 价格
     * @param size 数量
     * @param signatureType 签名类型（1: Email/Magic, 2: Browser Wallet, 0: EOA）
     * @param exchangeContract 签约用 exchange 合约地址；null 时用标准 CTF Exchange，neg risk 市场需传 Neg Risk Exchange
     * @return 签名的订单对象
     */
    fun createAndSignOrder(
        privateKey: String,
        makerAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        signatureType: Int = 2,
        exchangeContract: String? = null
    ): SignedOrderObject {
        try {
            // POLY_1271 (signatureType=3) 走独立的 ERC-7739 wrap 路径
            //   - maker / signer 必须同为 deposit wallet 地址
            //   - 调用方需透过 [createAndSignOrderForDepositWallet] 显式提供 depositWalletAddress
            // 当此函数被以 signatureType=3 调用时，要求 makerAddress 即为 depositWalletAddress。
            if (signatureType == 3) {
                return createAndSignOrderForDepositWallet(
                    privateKey = privateKey,
                    depositWalletAddress = makerAddress,
                    tokenId = tokenId,
                    side = side,
                    price = price,
                    size = size,
                    exchangeContract = exchangeContract
                )
            }

            // 1. 从私钥获取签名地址
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            val signerAddress = credentials.address.lowercase()

            // 2. 计算订单金额
            val amounts = calculateOrderAmounts(side, size, price)

            // 3. 生成 salt 和 timestamp（V2: timestamp 替代 nonce 保证唯一性）
            val salt = generateSalt()
            val timestamp = System.currentTimeMillis().toString()

            // 4. V2 字段默认值
            val metadata = "0x0000000000000000000000000000000000000000000000000000000000000000"
            val builder = "0x0000000000000000000000000000000000000000000000000000000000000000"

            // 5. 确保 maker 地址也是小写格式
            val makerAddressLower = makerAddress.lowercase()

            logger.debug("========== 订单签名前参数 (V2) ==========")
            logger.debug("订单方向: $side, 价格: $price, 数量: $size")
            logger.debug("Token ID: $tokenId")
            logger.debug("Maker: ${makerAddressLower.take(10)}...${makerAddressLower.takeLast(6)}")
            logger.debug("Signer: ${signerAddress.take(10)}...${signerAddress.takeLast(6)}")
            logger.debug("Amounts - Maker: ${amounts.makerAmount}, Taker: ${amounts.takerAmount}")
            logger.debug("Salt: $salt, Timestamp: $timestamp")
            logger.debug("Signature Type: $signatureType, Chain ID: $CHAIN_ID")

            // 6. 构建订单数据并签名
            val contract = exchangeContract?.takeIf { it.isNotBlank() } ?: EXCHANGE_CONTRACT
            val signature = signOrder(
                privateKey = privateKey,
                exchangeContract = contract,
                chainId = CHAIN_ID,
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = side.uppercase(),
                signatureType = signatureType,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder
            )

            // 7. 创建 V2 签名订单对象
            return SignedOrderObject(
                salt = salt,
                maker = makerAddressLower,
                signer = signerAddress,
                taker = "0x0000000000000000000000000000000000000000",
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = side.uppercase(),
                signatureType = signatureType,
                timestamp = timestamp,
                expiration = "0",
                metadata = metadata,
                builder = builder,
                signature = signature
            )
        } catch (e: Exception) {
            logger.error("创建并签名订单失败 (V2)", e)
            throw RuntimeException("创建并签名订单失败 (V2): ${e.message}", e)
        }
    }
    
    /**
     * 签名订单 V2（EIP-712）
     */
    private fun signOrder(
        privateKey: String,
        exchangeContract: String,
        chainId: Long,
        salt: Long,
        maker: String,
        signer: String,
        tokenId: String,
        makerAmount: String,
        takerAmount: String,
        side: String,
        signatureType: Int,
        timestamp: String,
        metadata: String,
        builder: String
    ): String {
        try {
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            val ecKeyPair = credentials.ecKeyPair

            val domainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeDomain(
                chainId = chainId,
                verifyingContract = exchangeContract.lowercase()
            )

            val orderHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeOrder(
                salt = salt,
                maker = maker,
                signer = signer,
                tokenId = tokenId,
                makerAmount = makerAmount,
                takerAmount = takerAmount,
                side = side,
                signatureType = signatureType,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder
            )

            val structuredHash = com.wrbug.polymarketbot.util.Eip712Encoder.hashStructuredData(
                domainSeparator = domainSeparator,
                messageHash = orderHash
            )

            val signature = org.web3j.crypto.Sign.signMessage(structuredHash, ecKeyPair, false)

            val rHex = org.web3j.utils.Numeric.toHexString(signature.r).removePrefix("0x").padStart(64, '0')
            val sHex = org.web3j.utils.Numeric.toHexString(signature.s).removePrefix("0x").padStart(64, '0')
            val vBytes = signature.v
            val vInt = if (vBytes.isNotEmpty()) vBytes[0].toInt() and 0xff else 0
            val vHex = "%02x".format(vInt)

            return "0x$rHex$sHex$vHex"
        } catch (e: Exception) {
            logger.error("订单签名失败 (V2)", e)
            throw RuntimeException("订单签名失败 (V2): ${e.message}", e)
        }
    }

    /**
     * 创建并签名 Deposit Wallet (POLY_1271 / signatureType=3) 订单
     *
     * 参考: https://docs.polymarket.com/trading/deposit-wallets#place-clob-orders
     *
     * 与 legacy 路径的关键差异：
     *   - signatureType = 3
     *   - maker = signer = depositWalletAddress
     *   - signature 是 ERC-7739 包覆后的 POLY_1271 签名（长度 > 65 byte）
     *
     * **Phase 2 实作状态**：ERC-7739 nested TypedDataSign 包覆部分需要参考
     * `@polymarket/clob-client-v2` 或 `py-clob-client-v2` 的实作做黄金样本对拷验证。
     * 在对拷未通过前，本方法 throw NotImplementedError 作为安全门。
     *
     * 调用方约束：
     *   - depositWalletAddress 必须已经透过 [com.wrbug.polymarketbot.service.system.RelayClientService.deployDepositWalletViaBuilderRelayer]
     *     部署完成并确认链上 STATE_CONFIRMED
     *   - privateKey 须为该 deposit wallet 的 owner / session signer
     */
    fun createAndSignOrderForDepositWallet(
        privateKey: String,
        depositWalletAddress: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        exchangeContract: String? = null
    ): SignedOrderObject {
        try {
            // 参考实作 (golden sample):
            //   py-clob-client-v2/py_clob_client_v2/order_utils/exchange_order_builder_v2.py
            //   _build_poly_1271_order_signature  (大约第 154-211 行)
            //   URL: https://raw.githubusercontent.com/Polymarket/py-clob-client-v2/main/py_clob_client_v2/order_utils/exchange_order_builder_v2.py
            //
            // 流程：
            //   1. inner Order：maker = signer = depositWalletAddress (小写, lowercase)
            //   2. 计算 V2 Order structHash (contents) 用既有 Eip712Encoder.encodeExchangeOrder
            //   3. 计算 CTF Exchange V2 domain separator (appDomainSeparator)
            //   4. 计算 ERC-7739 / Solady TypedDataSign digest =
            //        keccak256(0x1901 || appDomainSeparator || typedDataSignStructHash)
            //      typedDataSignStructHash 内含 wallet domain (DepositWallet/1/chainId/depositWallet/0x00..)
            //   5. owner / session signer 私钥对 digest 做 secp256k1 签名 (65 byte, v in {27,28})
            //   6. wire signature =
            //        innerSig(65) || appDomainSeparator(32) || contentsHash(32) ||
            //        utf8(ORDER_TYPE_STRING) || uint16BE(typeLen)

            // ---- 1) 私钥 / 签名地址 ----
            val cleanPrivateKey = privateKey.removePrefix("0x")
            val privateKeyBigInt = BigInteger(cleanPrivateKey, 16)
            val credentials = Credentials.create(privateKeyBigInt.toString(16))
            val ecKeyPair = credentials.ecKeyPair

            // ---- 2) 订单字段 ----
            val depositWalletLower = depositWalletAddress.lowercase()
            val amounts = calculateOrderAmounts(side, size, price)
            val salt = generateSalt()
            val timestamp = System.currentTimeMillis().toString()
            val metadata = "0x0000000000000000000000000000000000000000000000000000000000000000"
            val builder = "0x0000000000000000000000000000000000000000000000000000000000000000"
            val sideUpper = side.uppercase()

            val contract = (exchangeContract?.takeIf { it.isNotBlank() } ?: EXCHANGE_CONTRACT).lowercase()

            logger.debug("========== POLY_1271 (signatureType=3) 订单签名 ==========")
            logger.debug("Side: $sideUpper, price: $price, size: $size")
            logger.debug("TokenId: $tokenId")
            logger.debug("DepositWallet (maker=signer): ${depositWalletLower.take(10)}...${depositWalletLower.takeLast(6)}")
            logger.debug("Owner signer: ${credentials.address.lowercase()}")
            logger.debug("Exchange: $contract, ChainId: $CHAIN_ID")
            logger.debug("Salt: $salt, Timestamp: $timestamp")

            // ---- 3) inner Order structHash (contentsHash) ----
            val orderStructHash = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeOrder(
                salt = salt,
                maker = depositWalletLower,
                signer = depositWalletLower,
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = sideUpper,
                signatureType = 3,
                timestamp = timestamp,
                metadata = metadata,
                builder = builder
            )

            // ---- 4) CTF Exchange V2 domain separator ----
            val appDomainSeparator = com.wrbug.polymarketbot.util.Eip712Encoder.encodeExchangeDomain(
                chainId = CHAIN_ID,
                verifyingContract = contract
            )

            // ---- 5) 待签 digest (ERC-7739 TypedDataSign) ----
            val digest = com.wrbug.polymarketbot.util.Eip712Encoder.computePoly1271Digest(
                orderTypedDataHash = orderStructHash,
                ctfExchangeDomainSeparator = appDomainSeparator,
                depositWalletAddress = depositWalletLower,
                chainId = CHAIN_ID
            )

            // ---- 6) ECDSA 签名 (65 byte, v ∈ {27,28}) ----
            val sig = org.web3j.crypto.Sign.signMessage(digest, ecKeyPair, false)
            val rBytes = leftPad32(sig.r)
            val sBytes = leftPad32(sig.s)
            val vBytes = sig.v
            val vInt = if (vBytes.isNotEmpty()) (vBytes[0].toInt() and 0xff) else 0
            val rsv = ByteArray(65)
            System.arraycopy(rBytes, 0, rsv, 0, 32)
            System.arraycopy(sBytes, 0, rsv, 32, 32)
            rsv[64] = vInt.toByte()

            // ---- 7) wrap 成上链格式 ----
            val wrapped = com.wrbug.polymarketbot.util.Eip712Encoder.wrapPoly1271Signature(
                orderTypedDataHash = orderStructHash,
                ctfExchangeDomainSeparator = appDomainSeparator,
                depositWalletAddress = depositWalletLower,
                chainId = CHAIN_ID,
                signature65Bytes = rsv
            )
            val signatureHex = "0x" + wrapped.joinToString("") { "%02x".format(it.toInt() and 0xff) }

            // ---- 8) 组装回传 ----
            return SignedOrderObject(
                salt = salt,
                maker = depositWalletLower,
                signer = depositWalletLower,
                taker = "0x0000000000000000000000000000000000000000",
                tokenId = tokenId,
                makerAmount = amounts.makerAmount,
                takerAmount = amounts.takerAmount,
                side = sideUpper,
                signatureType = 3,
                timestamp = timestamp,
                expiration = "0",
                metadata = metadata,
                builder = builder,
                signature = signatureHex
            )
        } catch (e: Exception) {
            logger.error("POLY_1271 deposit wallet 订单签名失败", e)
            throw RuntimeException("POLY_1271 订单签名失败: ${e.message}", e)
        }
    }

    /**
     * 将 BigInteger 转成左对齐到 32 byte 的 byte array。
     * 用于将 web3j Sign.SignatureData.r/s (本身可能 <=32 byte) 标准化成 EIP-2 / ERC-1271 期待的固定 32-byte 形式。
     */
    private fun leftPad32(value: ByteArray): ByteArray {
        if (value.size == 32) return value
        val out = ByteArray(32)
        if (value.size < 32) {
            System.arraycopy(value, 0, out, 32 - value.size, value.size)
        } else {
            // 极端情况：BigInteger 可能多一字节符号位
            System.arraycopy(value, value.size - 32, out, 0, 32)
        }
        return out
    }

    /** 并发安全：确保同一毫秒内多次调用生成唯一 salt，避免 FIXED 模式预签双单等场景的 salt 碰撞 */
    private val saltSequence = AtomicLong(0)

    /**
     * 生成 salt（时间戳 + 自增序列，保证并发下唯一）
     * 兼容 Polymarket：salt 为 Long，时间戳主位 + 序列次位，与 TypeScript SDK 语义兼容
     */
    private fun generateSalt(): Long {
        val now = System.currentTimeMillis()
        val seq = saltSequence.incrementAndGet() and 0x3FF
        return now * 1000 + seq
    }
    
    /**
     * 将 BigDecimal 转换为 wei（指定小数位数）
     * 使用精确计算，不进行舍入，直接截断到指定小数位数
     */
    private fun parseUnits(value: BigDecimal, decimals: Int): BigInteger {
        // 先设置精度到指定小数位数（向下截断，不四舍五入）
        val scaledValue = value.setScale(decimals, RoundingMode.DOWN)
        val multiplier = BigInteger.TEN.pow(decimals)
        return scaledValue.multiply(BigDecimal(multiplier)).toBigInteger()
    }
    
    /**
     * 正常舍入（四舍五入）
     * 参考 clob-client/src/utilities.ts 的 roundNormal 函数
     * 只有当小数位数超过 decimals 时才进行舍入
     *
     * @param value 要舍入的数值
     * @param decimals 目标小数位数
     * @return 舍入后的数值
     */
    private fun roundNormal(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.HALF_UP)
    }
    
    /**
     * 向下舍入
     * 参考 clob-client/src/utilities.ts 的 roundDown 函数
     * 只有当小数位数超过 decimals 时才进行舍入
     *
     * @param value 要舍入的数值
     * @param decimals 目标小数位数
     * @return 舍入后的数值
     */
    private fun roundDown(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.DOWN)
    }

    /**
     * 向上舍入
     * 参考 clob-client/src/utilities.ts 的 roundUp 函数
     * 只有当小数位数超过 decimals 时才进行舍入
     *
     * @param value 要舍入的数值
     * @param decimals 目标小数位数
     * @return 舍入后的数值
     */
    private fun roundUp(value: BigDecimal, decimals: Int): BigDecimal {
        if (decimalPlaces(value) <= decimals) {
            return value
        }
        return value.setScale(decimals, RoundingMode.UP)
    }

    /**
     * 计算 BigDecimal 的小数位数
     * 参考 clob-client/src/utilities.ts 的 decimalPlaces 函数
     *
     * @param value 要计算的数值
     * @return 小数位数
     */
    private fun decimalPlaces(value: BigDecimal): Int {
        if (value.scale() <= 0) {
            return 0
        }
        // 去除尾部的零，获取真实的小数位数
        return value.stripTrailingZeros().scale()
    }
}

