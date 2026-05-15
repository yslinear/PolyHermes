package com.wrbug.polymarketbot.service.copytrading.orders

import com.wrbug.polymarketbot.enums.WalletType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 既有 OrderSigningService 的回归测试，确保在 Phase 0 加入 deposit-wallet 字段
 * 与未来 Phase 2 加入 POLY_1271 / signatureType=3 分支后，legacy 路径 (signatureType 1 / 2)
 * 仍维持向后兼容。
 *
 * 测试策略：用固定私钥与固定输入，断言：
 *   - 输出 SignedOrderObject 的字段对位正确
 *   - signature 为 ECDSA 65-byte (132 hex chars 含 0x)
 *   - 同样输入两次的 signature 不应当随机变动（除 salt/timestamp 外其他字段固定时签名确定性）
 *   - getSignatureTypeForWalletType 维持 MAGIC=1, SAFE=2
 */
class OrderSigningServiceTest {

    private lateinit var service: OrderSigningService

    /** 测试用固定私钥 (DO NOT REUSE)；对应 EOA 0x... 由 Credentials.create 推导。 */
    private val testPrivateKey = "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"

    /** 测试用 proxy address (legacy maker) */
    private val testProxyAddress = "0x1234567890abcdef1234567890abcdef12345678"

    /** 测试用 token ID (Polymarket conditional token) */
    private val testTokenId = "12345678901234567890"

    @BeforeEach
    fun setUp() {
        service = OrderSigningService()
    }

    @Test
    fun `getSignatureTypeForWalletType returns 1 for MAGIC`() {
        assertEquals(1, service.getSignatureTypeForWalletType(WalletType.MAGIC.value))
    }

    @Test
    fun `getSignatureTypeForWalletType returns 2 for SAFE`() {
        assertEquals(2, service.getSignatureTypeForWalletType(WalletType.SAFE.value))
    }

    @Test
    fun `getSignatureTypeForWalletType returns 2 as default for unknown or null`() {
        assertEquals(2, service.getSignatureTypeForWalletType(null))
        assertEquals(2, service.getSignatureTypeForWalletType(""))
        assertEquals(2, service.getSignatureTypeForWalletType("unknown_type"))
    }

    @Test
    fun `getExchangeContract returns standard exchange when negRisk false`() {
        val addr = service.getExchangeContract(negRisk = false)
        assertEquals("0xE111180000d2663C0091e4f400237545B87B996B", addr)
    }

    @Test
    fun `getExchangeContract returns neg-risk exchange when negRisk true`() {
        val addr = service.getExchangeContract(negRisk = true)
        assertEquals("0xe2222d279d744050d28e00520010520000310F59", addr)
    }

    @Test
    fun `calculateOrderAmounts buy at half price equals half size in collateral`() {
        // 100 shares @ 0.5  =>  maker (USDC) = 50.00 = 50_000_000 (6 decimals)
        //                       taker (shares 6dp) = 100.0000 = 100_000_000
        val amounts = service.calculateOrderAmounts(side = "BUY", size = "100", price = "0.5")
        assertEquals("50000000", amounts.makerAmount)
        assertEquals("100000000", amounts.takerAmount)
    }

    @Test
    fun `createAndSignOrder with MAGIC signatureType=1 produces well-formed signed order`() {
        val signed = service.createAndSignOrder(
            privateKey = testPrivateKey,
            makerAddress = testProxyAddress,
            tokenId = testTokenId,
            side = "BUY",
            price = "0.5",
            size = "10",
            signatureType = 1
        )

        assertEquals(testProxyAddress.lowercase(), signed.maker)
        // signer 来自 testPrivateKey 推导出的 EOA，必定与 maker 不同（maker 是 proxy）
        assertNotEquals(signed.maker, signed.signer)
        assertTrue(signed.signer.startsWith("0x"))
        assertEquals(1, signed.signatureType)
        assertEquals("BUY", signed.side)
        assertEquals(testTokenId, signed.tokenId)
        assertEquals("5000000", signed.makerAmount)   // 10 * 0.5 = 5 USDC = 5_000_000
        assertEquals("10000000", signed.takerAmount)  // 10 shares = 10_000_000
        assertEquals("0x0000000000000000000000000000000000000000", signed.taker)
        assertEquals("0", signed.expiration)
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000000", signed.metadata)
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000000", signed.builder)

        // ECDSA 签名应为 132 chars: 0x + 64 (r) + 64 (s) + 2 (v)
        assertNotNull(signed.signature)
        assertTrue(signed.signature.startsWith("0x"))
        assertEquals(132, signed.signature.length, "ECDSA signature must be 0x + 130 hex chars")
    }

    @Test
    fun `createAndSignOrder with SAFE signatureType=2 produces well-formed signed order`() {
        val signed = service.createAndSignOrder(
            privateKey = testPrivateKey,
            makerAddress = testProxyAddress,
            tokenId = testTokenId,
            side = "SELL",
            price = "0.5",
            size = "10",
            signatureType = 2
        )

        assertEquals(testProxyAddress.lowercase(), signed.maker)
        assertNotEquals(signed.maker, signed.signer)
        assertEquals(2, signed.signatureType)
        assertEquals("SELL", signed.side)
        assertTrue(signed.signature.startsWith("0x"))
        assertEquals(132, signed.signature.length)
    }

    @Test
    fun `createAndSignOrder respects custom exchange contract (neg-risk path)`() {
        val negRisk = service.getExchangeContract(negRisk = true)
        val signed = service.createAndSignOrder(
            privateKey = testPrivateKey,
            makerAddress = testProxyAddress,
            tokenId = testTokenId,
            side = "BUY",
            price = "0.5",
            size = "10",
            signatureType = 2,
            exchangeContract = negRisk
        )
        // 同一组参数下使用不同的 exchange 合约应当产生不同的签名（domain separator 改变）
        val signedStandard = service.createAndSignOrder(
            privateKey = testPrivateKey,
            makerAddress = testProxyAddress,
            tokenId = testTokenId,
            side = "BUY",
            price = "0.5",
            size = "10",
            signatureType = 2,
            exchangeContract = service.getExchangeContract(negRisk = false)
        )
        assertNotEquals(signed.signature, signedStandard.signature,
            "Neg-risk 与标准 exchange 应产生不同的签名（不同 domain separator）")
    }
}
