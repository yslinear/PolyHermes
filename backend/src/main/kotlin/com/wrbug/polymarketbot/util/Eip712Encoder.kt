package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * EIP-712 编码工具类
 * 手动实现 EIP-712 编码，避免 web3j StructuredDataEncoder 的 verifyingContract 问题
 * 
 * 参考 EIP-712 标准：https://eips.ethereum.org/EIPS/eip-712
 */
object Eip712Encoder {
    
    /**
     * Keccak-256 哈希
     */
    private fun keccak256(data: ByteArray): ByteArray {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash
    }
    
    /**
     * 编码字符串类型
     */
    private fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return keccak256(bytes)
    }
    
    /**
     * 编码地址类型（20 字节，左对齐到 32 字节）
     */
    private fun encodeAddress(address: String): ByteArray {
        val cleanAddress = address.removePrefix("0x").lowercase()
        val addressBytes = Numeric.hexStringToByteArray("0x$cleanAddress")
        // 地址是 20 字节，需要左侧填充 0 至 32 字节（即地址放在低 20 字节，高 12 字节为零）
        return ByteArray(32).apply {
            System.arraycopy(addressBytes, 0, this, 12, addressBytes.size)
        }
    }
    
    /**
     * 编码 uint256 类型（32 字节，大端序）
     */
    private fun encodeUint256(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val result = ByteArray(32)
        if (bytes.size <= 32) {
            // 左对齐
            System.arraycopy(bytes, 0, result, 32 - bytes.size, bytes.size)
        } else {
            // 如果超过 32 字节，取最后 32 字节
            System.arraycopy(bytes, bytes.size - 32, result, 0, 32)
        }
        return result
    }
    
    /**
     * 编码类型哈希（Type Hash）
     * 例如：encodeType("EIP712Domain", listOf("name", "version", "chainId"))
     */
    private fun encodeType(typeName: String, fields: List<Pair<String, String>>): ByteArray {
        val typeString = buildString {
            append(typeName)
            append("(")
            fields.forEachIndexed { index, (name, type) ->
                if (index > 0) append(",")
                append(type)
                append(" ")
                append(name)
            }
            append(")")
        }
        return keccak256(typeString.toByteArray(StandardCharsets.UTF_8))
    }
    
    /**
     * 编码域分隔符（Domain Separator）
     */
    fun encodeDomain(
        name: String,
        version: String,
        chainId: Long
    ): ByteArray {
        // EIP712Domain 类型定义（不包含 verifyingContract）
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256"
            )
        )
        
        // 编码域字段
        val nameHash = encodeString(name)
        val versionHash = encodeString(version)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        
        // 组合：keccak256(domainTypeHash || nameHash || versionHash || chainIdBytes)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码消息哈希（Message Hash）
     */
    fun encodeMessage(
        address: String,
        timestamp: String,
        nonce: BigInteger,
        message: String
    ): ByteArray {
        // ClobAuth 类型定义
        val clobAuthTypeHash = encodeType(
            "ClobAuth",
            listOf(
                "address" to "address",
                "timestamp" to "string",
                "nonce" to "uint256",
                "message" to "string"
            )
        )
        
        // 编码消息字段
        val addressBytes = encodeAddress(address)
        val timestampHash = encodeString(timestamp)
        val nonceBytes = encodeUint256(nonce)
        val messageHash = encodeString(message)
        
        // 组合：keccak256(clobAuthTypeHash || addressBytes || timestampHash || nonceBytes || messageHash)
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(clobAuthTypeHash, 0, encoded, 0, 32)
        System.arraycopy(addressBytes, 0, encoded, 32, 32)
        System.arraycopy(timestampHash, 0, encoded, 64, 32)
        System.arraycopy(nonceBytes, 0, encoded, 96, 32)
        System.arraycopy(messageHash, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 计算完整的结构化数据哈希
     * hash = keccak256("\x19\x01" || domainSeparator || messageHash)
     */
    fun hashStructuredData(
        domainSeparator: ByteArray,
        messageHash: ByteArray
    ): ByteArray {
        val prefix = byteArrayOf(0x19.toByte(), 0x01.toByte())
        val encoded = ByteArray(prefix.size + domainSeparator.size + messageHash.size)
        System.arraycopy(prefix, 0, encoded, 0, prefix.size)
        System.arraycopy(domainSeparator, 0, encoded, prefix.size, domainSeparator.size)
        System.arraycopy(messageHash, 0, encoded, prefix.size + domainSeparator.size, messageHash.size)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 ExchangeOrder V2 域分隔符
     * Domain: { name: "Polymarket CTF Exchange", version: "2", chainId: chainId, verifyingContract: exchangeContract }
     */
    fun encodeExchangeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )

        val nameHash = encodeString("Polymarket CTF Exchange")
        val versionHash = encodeString("2")
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        System.arraycopy(contractBytes, 0, encoded, 128, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 ExchangeOrder V2 消息哈希
     * V2 Order: { salt, maker, signer, tokenId, makerAmount, takerAmount, side, signatureType, timestamp, metadata, builder }
     */
    fun encodeExchangeOrder(
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
    ): ByteArray {
        val orderTypeHash = encodeType(
            "Order",
            listOf(
                "salt" to "uint256",
                "maker" to "address",
                "signer" to "address",
                "tokenId" to "uint256",
                "makerAmount" to "uint256",
                "takerAmount" to "uint256",
                "side" to "uint8",
                "signatureType" to "uint8",
                "timestamp" to "uint256",
                "metadata" to "bytes32",
                "builder" to "bytes32"
            )
        )

        val saltBytes = encodeUint256(BigInteger.valueOf(salt))
        val makerBytes = encodeAddress(maker)
        val signerBytes = encodeAddress(signer)
        val tokenIdBytes = encodeUint256(BigInteger(tokenId))
        val makerAmountBytes = encodeUint256(BigInteger(makerAmount))
        val takerAmountBytes = encodeUint256(BigInteger(takerAmount))

        val sideValue = when (side.uppercase()) {
            "BUY" -> 0
            "SELL" -> 1
            else -> throw IllegalArgumentException("side 必须是 BUY 或 SELL")
        }
        val sideBytes = encodeUint256(BigInteger.valueOf(sideValue.toLong()))
        val signatureTypeBytes = encodeUint256(BigInteger.valueOf(signatureType.toLong()))

        val timestampBytes = encodeUint256(BigInteger(timestamp))
        val metadataBytes = Numeric.hexStringToByteArray(metadata.removePrefix("0x").padStart(64, '0'))
        val builderBytes = Numeric.hexStringToByteArray(builder.removePrefix("0x").padStart(64, '0'))

        val encoded = ByteArray(32 * 12)  // typeHash + 11 个字段
        var offset = 0
        System.arraycopy(orderTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(saltBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signerBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(tokenIdBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(makerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(takerAmountBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(sideBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(signatureTypeBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(timestampBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(metadataBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(builderBytes, 0, encoded, offset, 32)

        return keccak256(encoded)
    }
    
    /**
     * 编码 Gnosis Safe 域分隔符
     * Domain: { chainId: uint256, verifyingContract: address }
     * 参考: builder-relayer-client/src/builder/safe.ts 的 createStructHash
     * 注意：TypeScript 的 domain 包含 chainId 和 verifyingContract
     */
    fun encodeSafeDomain(
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        
        val encoded = ByteArray(32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 32, 32)
        System.arraycopy(contractBytes, 0, encoded, 64, 32)
        
        return keccak256(encoded)
    }
    
    /**
     * 编码 Gnosis Safe SafeTx 消息哈希
     * SafeTx: { to, value, data, operation, safeTxGas, baseGas, gasPrice, gasToken, refundReceiver, nonce }
     * 参考: Gnosis Safe 合约的 SafeTx 结构
     */
    fun encodeSafeTx(
        to: String,
        value: BigInteger,
        data: String,
        operation: Int, // 0 = CALL, 1 = DELEGATECALL
        safeTxGas: BigInteger,
        baseGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: String,
        refundReceiver: String,
        nonce: BigInteger
    ): ByteArray {
        val safeTxTypeHash = encodeType(
            "SafeTx",
            listOf(
                "to" to "address",
                "value" to "uint256",
                "data" to "bytes",
                "operation" to "uint8",
                "safeTxGas" to "uint256",
                "baseGas" to "uint256",
                "gasPrice" to "uint256",
                "gasToken" to "address",
                "refundReceiver" to "address",
                "nonce" to "uint256"
            )
        )
        
        // 编码字段
        val toBytes = encodeAddress(to)
        val valueBytes = encodeUint256(value)
        // data 是 bytes 类型，需要先计算 keccak256 哈希
        val dataBytes = if (data.isBlank() || data == "0x") {
            ByteArray(32) // 空 bytes 的哈希
        } else {
            val cleanData = data.removePrefix("0x")
            val dataByteArray = Numeric.hexStringToByteArray("0x$cleanData")
            keccak256(dataByteArray)
        }
        val operationBytes = encodeUint256(BigInteger.valueOf(operation.toLong()))
        val safeTxGasBytes = encodeUint256(safeTxGas)
        val baseGasBytes = encodeUint256(baseGas)
        val gasPriceBytes = encodeUint256(gasPrice)
        val gasTokenBytes = encodeAddress(gasToken)
        val refundReceiverBytes = encodeAddress(refundReceiver)
        val nonceBytes = encodeUint256(nonce)
        
        // 组合所有字段
        val encoded = ByteArray(32 * 11)  // 11 个字段，每个 32 字节
        var offset = 0
        System.arraycopy(safeTxTypeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(toBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(valueBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(dataBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(operationBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(safeTxGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(baseGasBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasPriceBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(gasTokenBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(refundReceiverBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(nonceBytes, 0, encoded, offset, 32)
        
        return keccak256(encoded)
    }

    /**
     * SafeCreate 用 EIP712 域（Polymarket Contract Proxy Factory）
     * Domain: EIP712Domain(string name, uint256 chainId, address verifyingContract)
     * 参考: builder-relayer-client/src/builder/create.ts createSafeCreateSignature
     */
    fun encodeSafeCreateDomain(
        name: String,
        chainId: Long,
        verifyingContract: String
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )
        val nameHash = encodeString(name)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 64, 32)
        System.arraycopy(contractBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }

    /**
     * CreateProxy 消息哈希（SafeCreate 签名用）
     * CreateProxy(address paymentToken, uint256 payment, address paymentReceiver)
     */
    fun encodeCreateProxyMessage(
        paymentToken: String,
        payment: BigInteger,
        paymentReceiver: String
    ): ByteArray {
        val typeHash = encodeType(
            "CreateProxy",
            listOf(
                "paymentToken" to "address",
                "payment" to "uint256",
                "paymentReceiver" to "address"
            )
        )
        val tokenBytes = encodeAddress(paymentToken)
        val paymentBytes = encodeUint256(payment)
        val receiverBytes = encodeAddress(paymentReceiver)
        val encoded = ByteArray(32 + 32 + 32 + 32)
        System.arraycopy(typeHash, 0, encoded, 0, 32)
        System.arraycopy(tokenBytes, 0, encoded, 32, 32)
        System.arraycopy(paymentBytes, 0, encoded, 64, 32)
        System.arraycopy(receiverBytes, 0, encoded, 96, 32)
        return keccak256(encoded)
    }

    /**
     * 编码 DepositWallet EIP-712 域分隔符
     * Domain: { name:"DepositWallet", version:"1", chainId, verifyingContract: depositWalletAddress }
     * 参考: https://docs.polymarket.com/trading/deposit-wallets#submit-a-deposit-wallet-batch
     */
    fun encodeDepositWalletDomain(
        chainId: Long,
        verifyingContract: String,
        name: String = "DepositWallet",
        version: String = "1"
    ): ByteArray {
        val domainTypeHash = encodeType(
            "EIP712Domain",
            listOf(
                "name" to "string",
                "version" to "string",
                "chainId" to "uint256",
                "verifyingContract" to "address"
            )
        )

        val nameHash = encodeString(name)
        val versionHash = encodeString(version)
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val contractBytes = encodeAddress(verifyingContract)

        val encoded = ByteArray(32 + 32 + 32 + 32 + 32)
        System.arraycopy(domainTypeHash, 0, encoded, 0, 32)
        System.arraycopy(nameHash, 0, encoded, 32, 32)
        System.arraycopy(versionHash, 0, encoded, 64, 32)
        System.arraycopy(chainIdBytes, 0, encoded, 96, 32)
        System.arraycopy(contractBytes, 0, encoded, 128, 32)

        return keccak256(encoded)
    }

    /**
     * 编码单笔 deposit wallet Call (EIP-712 nested struct)
     *   Call(address target, uint256 value, bytes data)
     * structHash = keccak256(typeHash || pad32(target) || pad32(value) || keccak256(data))
     */
    private fun encodeDepositWalletCallStruct(target: String, value: BigInteger, data: String): ByteArray {
        val typeHash = CALL_TYPE_HASH
        val targetBytes = encodeAddress(target)
        val valueBytes = encodeUint256(value)
        val dataBytes = Numeric.hexStringToByteArray(if (data.startsWith("0x")) data else "0x$data")
        val dataHash = keccak256(dataBytes)
        val encoded = ByteArray(32 * 4)
        System.arraycopy(typeHash, 0, encoded, 0, 32)
        System.arraycopy(targetBytes, 0, encoded, 32, 32)
        System.arraycopy(valueBytes, 0, encoded, 64, 32)
        System.arraycopy(dataHash, 0, encoded, 96, 32)
        return keccak256(encoded)
    }

    /**
     * 编码 deposit wallet Batch 消息哈希
     * 参考: https://docs.polymarket.com/trading/deposit-wallets#submit-a-deposit-wallet-batch
     *
     *   Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)
     *   Call(address target,uint256 value,bytes data)
     *
     * 注意：EIP-712 嵌套类型按字母序串接到 main type 后；这里 "Batch(...)Call(...)"。
     * calls 数组的 hash = keccak256(callStructHash_0 || callStructHash_1 || ...)
     */
    fun encodeDepositWalletBatch(
        wallet: String,
        nonce: BigInteger,
        deadline: BigInteger,
        calls: List<DepositWalletCallInput>
    ): ByteArray {
        require(calls.isNotEmpty()) { "DepositWallet Batch must contain at least one Call" }
        val typeString = "Batch(address wallet,uint256 nonce,uint256 deadline,Call[] calls)" +
            "Call(address target,uint256 value,bytes data)"
        val typeHash = keccak256(typeString.toByteArray(StandardCharsets.UTF_8))

        val walletBytes = encodeAddress(wallet)
        val nonceBytes = encodeUint256(nonce)
        val deadlineBytes = encodeUint256(deadline)

        val callsConcat = ByteArray(32 * calls.size)
        calls.forEachIndexed { idx, call ->
            val callHash = encodeDepositWalletCallStruct(call.target, call.value, call.data)
            System.arraycopy(callHash, 0, callsConcat, idx * 32, 32)
        }
        val callsArrayHash = keccak256(callsConcat)

        val encoded = ByteArray(32 * 5)
        System.arraycopy(typeHash, 0, encoded, 0, 32)
        System.arraycopy(walletBytes, 0, encoded, 32, 32)
        System.arraycopy(nonceBytes, 0, encoded, 64, 32)
        System.arraycopy(deadlineBytes, 0, encoded, 96, 32)
        System.arraycopy(callsArrayHash, 0, encoded, 128, 32)
        return keccak256(encoded)
    }

    /**
     * Deposit wallet Call 输入（与 BuilderRelayerApi.DepositWalletCall 解耦的内部表示）
     */
    data class DepositWalletCallInput(
        val target: String,
        val value: BigInteger,
        val data: String  // 0x-prefixed hex 或 raw hex 均可
    )

    // ============================================================
    // ERC-7739 / POLY_1271 (signatureType = 3) wrapped order signing
    // ============================================================
    //
    // 参考实作（黄金样本来源）:
    //   py-clob-client-v2/py_clob_client_v2/order_utils/exchange_order_builder_v2.py
    //     - https://raw.githubusercontent.com/Polymarket/py-clob-client-v2/main/py_clob_client_v2/order_utils/exchange_order_builder_v2.py
    //     - 关键函数: _build_poly_1271_order_signature (第 154-211 行附近)
    //
    // 概念：
    //   1. 内部 contents = V2 Order 的 EIP-712 structHash（即既有 encodeExchangeOrder 的输出）
    //   2. Solady / ERC-7739 包装：
    //        TypedDataSign(Order contents,string name,string version,uint256 chainId,
    //                     address verifyingContract,bytes32 salt)
    //        Order(uint256 salt,address maker,address signer,uint256 tokenId,
    //              uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType,
    //              uint256 timestamp,bytes32 metadata,bytes32 builder)
    //      wallet 域字段：name="DepositWallet", version="1", chainId=current,
    //                    verifyingContract=depositWalletAddress, salt=0x00..00
    //   3. 最终被签名的 digest = keccak256(0x1901 || appDomainSeparator || typedDataSignStructHash)
    //      其中 appDomainSeparator = CTF Exchange V2 EIP712Domain separator
    //   4. wire signature 格式（appended bytes）:
    //        0x || innerSig(65B, r||s||v) ||
    //              appDomainSeparator(32B) ||
    //              contentsHash(32B) ||
    //              contentsType(utf-8 bytes of ORDER_TYPE_STRING) ||
    //              uint16BE(contentsTypeLen)

    /** ORDER_TYPE_STRING (UTF-8)，对应 ERC-7739 wrap 中的 contentsDescr */
    private const val ORDER_TYPE_STRING: String =
        "Order(uint256 salt,address maker,address signer,uint256 tokenId," +
            "uint256 makerAmount,uint256 takerAmount,uint8 side,uint8 signatureType," +
            "uint256 timestamp,bytes32 metadata,bytes32 builder)"

    /**
     * DepositWallet Call(...) 单笔结构的 EIP-712 typeHash
     * 提取为常量避免在每次 batch 调用中重复计算 keccak256
     */
    private val CALL_TYPE_HASH: ByteArray =
        keccak256("Call(address target,uint256 value,bytes data)".toByteArray(StandardCharsets.UTF_8))

    /**
     * TypedDataSign type hash for POLY_1271 wrapped signature
     *
     * 参考 exchange_order_builder_v2.py 第 22-32 行 SOLADY_TYPE_STRING / SOLADY_TYPE_HASH
     */
    private fun soladyTypeHash(): ByteArray {
        val typeString =
            "TypedDataSign(Order contents,string name,string version,uint256 chainId," +
                "address verifyingContract,bytes32 salt)" +
                ORDER_TYPE_STRING
        return keccak256(typeString.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 计算 ERC-7739 TypedDataSign struct hash
     *
     * 等价于 py 参考实作 _build_poly_1271_order_signature 中的 typed_data_sign_struct_hash 段：
     *   keccak256(
     *     soladyTypeHash || contentsHash ||
     *     keccak256("DepositWallet") || keccak256("1") ||
     *     uint256(chainId) || pad32(depositWalletAddress) || bytes32(0)
     *   )
     */
    private fun encodeTypedDataSignStructHash(
        contentsHash: ByteArray,
        chainId: Long,
        depositWalletAddress: String
    ): ByteArray {
        val typeHash = soladyTypeHash()
        val nameHash = keccak256("DepositWallet".toByteArray(StandardCharsets.UTF_8))
        val versionHash = keccak256("1".toByteArray(StandardCharsets.UTF_8))
        val chainIdBytes = encodeUint256(BigInteger.valueOf(chainId))
        val verifyingBytes = encodeAddress(depositWalletAddress)
        val saltBytes = ByteArray(32)  // 固定全零 salt

        val encoded = ByteArray(32 * 7)
        var offset = 0
        System.arraycopy(typeHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(contentsHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(nameHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(versionHash, 0, encoded, offset, 32); offset += 32
        System.arraycopy(chainIdBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(verifyingBytes, 0, encoded, offset, 32); offset += 32
        System.arraycopy(saltBytes, 0, encoded, offset, 32)
        return keccak256(encoded)
    }

    /**
     * 计算 POLY_1271 待签 digest
     *
     * digest = keccak256(0x1901 || ctfExchangeDomainSeparator || typedDataSignStructHash)
     *
     * 调用方在此 digest 上以 owner / session signer 私钥做 secp256k1 签名，
     * 得到 65-byte (r||s||v) 后再交给 [wrapPoly1271Signature] 组装上链格式。
     */
    fun computePoly1271Digest(
        orderTypedDataHash: ByteArray,
        ctfExchangeDomainSeparator: ByteArray,
        depositWalletAddress: String,
        chainId: Long
    ): ByteArray {
        require(orderTypedDataHash.size == 32) { "orderTypedDataHash 必须为 32 bytes" }
        require(ctfExchangeDomainSeparator.size == 32) { "ctfExchangeDomainSeparator 必须为 32 bytes" }
        val tdsStructHash = encodeTypedDataSignStructHash(
            contentsHash = orderTypedDataHash,
            chainId = chainId,
            depositWalletAddress = depositWalletAddress
        )
        return hashStructuredData(ctfExchangeDomainSeparator, tdsStructHash)
    }

    /**
     * 组装 POLY_1271 / ERC-7739 wrapped 签名上链格式
     *
     * 参考: exchange_order_builder_v2.py 第 200-210 行
     *   return (
     *     "0x"
     *     + inner_signature                  # 65 bytes (r||s||v, v in {27,28})
     *     + self.app_domain_separator.hex()  # 32 bytes
     *     + contents_hash.hex()              # 32 bytes
     *     + contents_type                    # utf-8 bytes of ORDER_TYPE_STRING
     *     + contents_type_len                # uint16 BE
     *   )
     *
     * @param orderTypedDataHash V2 Order structHash（即 encodeExchangeOrder 结果）
     * @param ctfExchangeDomainSeparator CTF Exchange V2 EIP-712 domain separator
     * @param depositWalletAddress deposit wallet 地址（即 ERC-1271 验证合约）
     *                              注意：当前实作未使用此参数，但保留在 API 中以便日后可读性 / 校验
     * @param chainId 链 ID（同上，保留以维持调用方 ergonomics）
     * @param signature65Bytes secp256k1 签名 r||s||v，长度必须为 65；v 应为 27/28（web3j Sign.signMessage(..., false)）
     * @return wrap 后的完整 signature 字节数组（含上述五段拼接）
     */
    @Suppress("UNUSED_PARAMETER")
    fun wrapPoly1271Signature(
        orderTypedDataHash: ByteArray,
        ctfExchangeDomainSeparator: ByteArray,
        depositWalletAddress: String,
        chainId: Long,
        signature65Bytes: ByteArray
    ): ByteArray {
        require(signature65Bytes.size == 65) {
            "POLY_1271 inner signature 必须为 65 bytes (r||s||v)，实际 ${signature65Bytes.size}"
        }
        require(orderTypedDataHash.size == 32) { "orderTypedDataHash 必须为 32 bytes" }
        require(ctfExchangeDomainSeparator.size == 32) { "ctfExchangeDomainSeparator 必须为 32 bytes" }

        val contentsTypeBytes = ORDER_TYPE_STRING.toByteArray(StandardCharsets.UTF_8)
        val typeLen = contentsTypeBytes.size
        require(typeLen <= 0xFFFF) { "contentsType 长度超过 uint16 上限" }
        val typeLenBytes = byteArrayOf(
            ((typeLen shr 8) and 0xFF).toByte(),
            (typeLen and 0xFF).toByte()
        )

        // 65 + 32 + 32 + N + 2
        val total = 65 + 32 + 32 + contentsTypeBytes.size + 2
        val out = ByteArray(total)
        var offset = 0
        System.arraycopy(signature65Bytes, 0, out, offset, 65); offset += 65
        System.arraycopy(ctfExchangeDomainSeparator, 0, out, offset, 32); offset += 32
        System.arraycopy(orderTypedDataHash, 0, out, offset, 32); offset += 32
        System.arraycopy(contentsTypeBytes, 0, out, offset, contentsTypeBytes.size)
        offset += contentsTypeBytes.size
        System.arraycopy(typeLenBytes, 0, out, offset, 2)
        return out
    }
}

