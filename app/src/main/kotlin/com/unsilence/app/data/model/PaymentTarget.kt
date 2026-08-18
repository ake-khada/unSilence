package com.unsilence.app.data.model

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** A payment destination found in user-authored note content. */
@Immutable
sealed interface PaymentTarget {
    /** Exact value to place on the clipboard. */
    val copyText: String

    /** URI handed to an installed wallet, only after an explicit user tap. */
    val walletUri: String

    /** Compact value shown in the card body. */
    val displayValue: String

    @Immutable
    data class LightningInvoice(
        val invoice: String,
        val amountMsats: Long?,
        val network: LightningNetwork,
    ) : PaymentTarget {
        override val copyText: String get() = invoice
        override val walletUri: String get() = "lightning:$invoice"
        override val displayValue: String get() = invoice
    }

    @Immutable
    data class LightningAddress(
        val address: String,
        val domain: String,
        val explicitScheme: Boolean,
    ) : PaymentTarget {
        override val copyText: String get() = address
        override val walletUri: String get() = "lightning:$address"
        override val displayValue: String get() = address
    }

    @Immutable
    data class Lnurl(
        val code: String,
        val decodedUrl: String,
        val host: String,
    ) : PaymentTarget {
        override val copyText: String get() = code
        override val walletUri: String get() = "lightning:$code"
        override val displayValue: String get() = code
    }

    @Immutable
    data class Bitcoin(
        val address: String,
        val network: BitcoinNetwork,
        val format: BitcoinAddressFormat,
        val amountSats: Long?,
        val paymentUri: String,
        val explicitUri: Boolean,
    ) : PaymentTarget {
        override val copyText: String get() = if (explicitUri) paymentUri else address
        override val walletUri: String get() = paymentUri
        override val displayValue: String get() = address
    }
}

@Immutable
enum class LightningNetwork {
    MAINNET,
    TESTNET,
    REGTEST,
    SIGNET,
    SIMNET,
}

@Immutable
enum class BitcoinNetwork {
    MAINNET,
    TESTNET,
    REGTEST,
}

@Immutable
enum class BitcoinAddressFormat {
    P2PKH,
    P2SH,
    SEGWIT,
    TAPROOT,
    WITNESS,
}

internal data class LocatedPaymentTarget(
    val start: Int,
    val endExclusive: Int,
    val target: PaymentTarget,
)

/** Bare LUD-16 syntax is also valid email/NIP-05 syntax, so require trusted
 * profile metadata before presenting it as a payment destination. */
internal fun PaymentTarget.shouldRenderAsCard(knownLightningAddress: String?): Boolean =
    when (this) {
        is PaymentTarget.LightningAddress -> explicitScheme ||
            knownLightningAddress?.trim()?.equals(address, ignoreCase = true) == true
        else -> true
    }

/**
 * Locates payment destinations without network access.
 *
 * BOLT-11, LNURL, Base58Check, Bech32, and Bech32m candidates must pass their
 * checksums before they become cards. LUD-16 is necessarily syntax-only: the
 * format is intentionally email-shaped and can only be proven by contacting
 * the domain. Bare candidates therefore require corroboration from the note
 * author's advertised lud16 at render time; an explicit lightning: URI does
 * not. Content rendering never probes arbitrary domains implicitly.
 */
internal object PaymentTargetParser {
    private const val MAX_CANDIDATES_PER_FORMAT = 24
    private const val MAX_TARGETS = 12
    private const val BECH32M_CONSTANT = 0x2bc830a3
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BASE58_CHARSET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private const val BECH32_TOKEN_CHARS = "02-9ac-hj-np-zAC-HJ-NP-Z"
    private const val BASE58_TOKEN_CHARS = "1-9A-HJ-NP-Za-km-z"
    private const val DOMAIN_LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"

    private val bolt11Regex = Regex(
        """(?<![A-Za-z0-9])(?:[Ll][Ii][Gg][Hh][Tt][Nn][Ii][Nn][Gg]:)?(?:lnbcrt|lntbs|lnbc|lntb|lnsb)(?:\d+[munp]?)?1[$BECH32_TOKEN_CHARS]{20,}(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    private val lnurlRegex = Regex(
        """(?<![A-Za-z0-9])(?:[Ll][Ii][Gg][Hh][Tt][Nn][Ii][Nn][Gg]:)?lnurl1[$BECH32_TOKEN_CHARS]{20,}(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    private val bitcoinRegex = Regex(
        """(?<![A-Za-z0-9])([Bb][Ii][Tt][Cc][Oo][Ii][Nn]:)?((?:[13mn2][$BASE58_TOKEN_CHARS]{25,34})|(?:(?:[bB][cC]|[tT][bB]|[bB][cC][rR][tT])1[$BECH32_TOKEN_CHARS]{6,90}))(?:\?([^\s<>{}\[\]()]*)?)?(?![A-Za-z0-9])""",
    )

    private val lightningAddressRegex = Regex(
        """(?<![A-Za-z0-9._+\-])(?:[Ll][Ii][Gg][Hh][Tt][Nn][Ii][Nn][Gg]:)?([a-z0-9_.+\-]{1,64}@${DOMAIN_LABEL}(?:\.${DOMAIN_LABEL})+)(?![A-Za-z0-9.-])""",
    )

    fun findAll(content: String): List<LocatedPaymentTarget> {
        if (content.isBlank()) return emptyList()

        val candidates = ArrayList<LocatedPaymentTarget>()
        bolt11Regex.findAll(content).take(MAX_CANDIDATES_PER_FORMAT).forEach { match ->
            parseBolt11(match.value)?.let { target ->
                candidates += LocatedPaymentTarget(match.range.first, match.range.last + 1, target)
            }
        }
        lnurlRegex.findAll(content).take(MAX_CANDIDATES_PER_FORMAT).forEach { match ->
            parseLnurl(match.value)?.let { target ->
                candidates += LocatedPaymentTarget(match.range.first, match.range.last + 1, target)
            }
        }
        bitcoinRegex.findAll(content).take(MAX_CANDIDATES_PER_FORMAT).forEach { match ->
            parseBitcoin(match)?.let { target ->
                candidates += LocatedPaymentTarget(match.range.first, match.range.last + 1, target)
            }
        }
        lightningAddressRegex.findAll(content).take(MAX_CANDIDATES_PER_FORMAT).forEach { match ->
            parseLightningAddress(match.value)?.let { target ->
                candidates += LocatedPaymentTarget(match.range.first, match.range.last + 1, target)
            }
        }

        // Prefer the longest candidate at a position (for example,
        // lightning:lnurl... over its nested bare lnurl... substring).
        candidates.sortWith(
            compareBy<LocatedPaymentTarget> { it.start }
                .thenByDescending { it.endExclusive - it.start },
        )
        val selected = ArrayList<LocatedPaymentTarget>(minOf(candidates.size, MAX_TARGETS))
        var cursor = 0
        for (candidate in candidates) {
            if (candidate.start < cursor) continue
            selected += candidate
            cursor = candidate.endExclusive
            if (selected.size == MAX_TARGETS) break
        }
        return selected
    }

    private fun parseBolt11(raw: String): PaymentTarget.LightningInvoice? {
        val invoice = raw.removeScheme("lightning:")
        val valid = runCatching { LnInvoiceUtil.decodeUnlimitedLength(invoice) }.getOrDefault(false)
        if (!valid) return null

        val lower = invoice.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator <= 0) return null
        val hrp = lower.take(separator)
        val (network, amountPart) = when {
            hrp.startsWith("lnbcrt") -> LightningNetwork.REGTEST to hrp.removePrefix("lnbcrt")
            hrp.startsWith("lntbs") -> LightningNetwork.SIGNET to hrp.removePrefix("lntbs")
            hrp.startsWith("lnbc") -> LightningNetwork.MAINNET to hrp.removePrefix("lnbc")
            hrp.startsWith("lntb") -> LightningNetwork.TESTNET to hrp.removePrefix("lntb")
            hrp.startsWith("lnsb") -> LightningNetwork.SIMNET to hrp.removePrefix("lnsb")
            else -> return null
        }
        val amountMsats = if (amountPart.isEmpty()) null else decodeBolt11AmountMsats(amountPart) ?: return null
        return PaymentTarget.LightningInvoice(
            invoice = invoice,
            amountMsats = amountMsats,
            network = network,
        )
    }

    private fun decodeBolt11AmountMsats(amountPart: String): Long? {
        if (amountPart.isEmpty()) return null
        val suffix = amountPart.last()
        val digits = if (suffix in "munp") amountPart.dropLast(1) else amountPart
        val amount = digits.toLongOrNull() ?: return null
        return runCatching {
            when (suffix) {
                'm' -> Math.multiplyExact(amount, 100_000_000L)
                'u' -> Math.multiplyExact(amount, 100_000L)
                'n' -> Math.multiplyExact(amount, 100L)
                'p' -> amount.takeIf { it % 10L == 0L }?.div(10L)
                else -> Math.multiplyExact(amount, 100_000_000_000L)
            }
        }.getOrNull()
    }

    private fun parseLnurl(raw: String): PaymentTarget.Lnurl? {
        val code = raw.removeScheme("lightning:")
        val decoded = decodeBech32(code, enforceLengthLimit = false) ?: return null
        if (decoded.hrp != "lnurl" || decoded.encoding != Bech32Encoding.BECH32) return null
        val bytes = convertBits(decoded.data, fromBits = 5, toBits = 8, pad = false) ?: return null
        val url = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val allowedTransport = uri.scheme.equals("https", ignoreCase = true) ||
            (uri.scheme.equals("http", ignoreCase = true) && host.endsWith(".onion", ignoreCase = true))
        if (!allowedTransport) return null
        return PaymentTarget.Lnurl(code = code, decodedUrl = url, host = host)
    }

    private fun parseLightningAddress(raw: String): PaymentTarget.LightningAddress? {
        val explicitScheme = raw.startsWith("lightning:", ignoreCase = true)
        val address = raw.removeScheme("lightning:")
        if (address.length > 254 || address.count { it == '@' } != 1) return null
        val username = address.substringBefore('@')
        val domain = address.substringAfter('@')
        // LUD-16 is stricter than email: lowercase a-z0-9-_. plus optional + tags.
        if (username.isEmpty() || username.length > 64 || username.any { it !in LUD16_USERNAME_CHARS }) return null
        if (username != username.lowercase()) return null
        if (!isValidDomain(domain)) return null
        return PaymentTarget.LightningAddress(
            address = address,
            domain = domain.lowercase(),
            explicitScheme = explicitScheme,
        )
    }

    private fun parseBitcoin(match: MatchResult): PaymentTarget.Bitcoin? {
        val explicitUri = match.groups[1] != null
        val address = match.groups[2]?.value ?: return null
        val query = match.groups[3]?.value
        if (!explicitUri && query != null) return null

        val decoded = decodeBase58Address(address) ?: decodeSegwitAddress(address) ?: return null
        val paymentUri = if (explicitUri) match.value else "bitcoin:$address"
        return PaymentTarget.Bitcoin(
            address = address,
            network = decoded.network,
            format = decoded.format,
            amountSats = parseBitcoinAmountSats(query),
            paymentUri = paymentUri,
            explicitUri = explicitUri,
        )
    }

    private fun parseBitcoinAmountSats(query: String?): Long? {
        if (query.isNullOrBlank()) return null
        val amountValues = query.split('&').mapNotNull { parameter ->
            val separator = parameter.indexOf('=')
            if (separator <= 0 || !parameter.take(separator).equals("amount", ignoreCase = true)) null
            else parameter.substring(separator + 1)
        }
        if (amountValues.size != 1) return null
        val raw = amountValues.single()
        if (!raw.matches(Regex("""\d+(?:\.\d{0,8})?"""))) return null
        return runCatching {
            BigDecimal(raw).movePointRight(8).longValueExact().takeIf { it >= 0L }
        }.getOrNull()
    }

    private data class DecodedBitcoinAddress(
        val network: BitcoinNetwork,
        val format: BitcoinAddressFormat,
    )

    private fun decodeBase58Address(address: String): DecodedBitcoinAddress? {
        if (address.length !in 26..35 || address.any { it !in BASE58_CHARSET }) return null
        var value = BigInteger.ZERO
        for (char in address) {
            value = value.multiply(BigInteger.valueOf(58L))
                .add(BigInteger.valueOf(BASE58_CHARSET.indexOf(char).toLong()))
        }
        var body = value.toByteArray()
        if (body.size > 1 && body[0] == 0.toByte()) body = body.copyOfRange(1, body.size)
        val leadingZeros = address.takeWhile { it == '1' }.length
        val decoded = ByteArray(leadingZeros + body.size)
        body.copyInto(decoded, destinationOffset = leadingZeros)
        if (decoded.size != 25) return null

        val payload = decoded.copyOfRange(0, 21)
        val expectedChecksum = doubleSha256(payload).copyOfRange(0, 4)
        if (!decoded.copyOfRange(21, 25).contentEquals(expectedChecksum)) return null
        return when (decoded[0].toInt() and 0xff) {
            0x00 -> DecodedBitcoinAddress(BitcoinNetwork.MAINNET, BitcoinAddressFormat.P2PKH)
            0x05 -> DecodedBitcoinAddress(BitcoinNetwork.MAINNET, BitcoinAddressFormat.P2SH)
            0x6f -> DecodedBitcoinAddress(BitcoinNetwork.TESTNET, BitcoinAddressFormat.P2PKH)
            0xc4 -> DecodedBitcoinAddress(BitcoinNetwork.TESTNET, BitcoinAddressFormat.P2SH)
            else -> null
        }
    }

    private fun decodeSegwitAddress(address: String): DecodedBitcoinAddress? {
        val decoded = decodeBech32(address, enforceLengthLimit = true) ?: return null
        val network = when (decoded.hrp) {
            "bc" -> BitcoinNetwork.MAINNET
            "tb" -> BitcoinNetwork.TESTNET
            "bcrt" -> BitcoinNetwork.REGTEST
            else -> return null
        }
        if (decoded.data.isEmpty()) return null
        val witnessVersion = decoded.data[0]
        if (witnessVersion !in 0..16) return null
        val program = convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, pad = false)
            ?: return null
        if (program.size !in 2..40) return null
        if (witnessVersion == 0 && program.size != 20 && program.size != 32) return null
        if (witnessVersion == 0 && decoded.encoding != Bech32Encoding.BECH32) return null
        if (witnessVersion > 0 && decoded.encoding != Bech32Encoding.BECH32M) return null
        val format = when {
            witnessVersion == 0 -> BitcoinAddressFormat.SEGWIT
            witnessVersion == 1 && program.size == 32 -> BitcoinAddressFormat.TAPROOT
            else -> BitcoinAddressFormat.WITNESS
        }
        return DecodedBitcoinAddress(network, format)
    }

    private enum class Bech32Encoding { BECH32, BECH32M }

    private data class DecodedBech32(
        val hrp: String,
        val data: IntArray,
        val encoding: Bech32Encoding,
    )

    private fun decodeBech32(value: String, enforceLengthLimit: Boolean): DecodedBech32? {
        if (value.isEmpty() || (enforceLengthLimit && value.length > 90)) return null
        if (value.any { it.code !in 33..126 }) return null
        val hasLower = value.any(Char::isLowerCase)
        val hasUpper = value.any(Char::isUpperCase)
        if (hasLower && hasUpper) return null

        val lower = value.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 7 > lower.length) return null
        val hrp = lower.take(separator)
        val values = IntArray(lower.length - separator - 1)
        for (index in values.indices) {
            val decoded = BECH32_CHARSET.indexOf(lower[separator + 1 + index])
            if (decoded < 0) return null
            values[index] = decoded
        }
        val checksum = bech32Polymod(expandHrp(hrp) + values)
        val encoding = when (checksum) {
            1 -> Bech32Encoding.BECH32
            BECH32M_CONSTANT -> Bech32Encoding.BECH32M
            else -> return null
        }
        return DecodedBech32(hrp, values.copyOfRange(0, values.size - 6), encoding)
    }

    private fun expandHrp(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (index in hrp.indices) {
            result[index] = hrp[index].code ushr 5
            result[hrp.length + 1 + index] = hrp[index].code and 31
        }
        return result
    }

    private fun bech32Polymod(values: IntArray): Int {
        val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (bit in generators.indices) {
                if (((top ushr bit) and 1) != 0) checksum = checksum xor generators[bit]
            }
        }
        return checksum
    }

    private fun convertBits(
        values: IntArray,
        fromBits: Int,
        toBits: Int,
        pad: Boolean,
    ): ByteArray? {
        var accumulator = 0
        var bitCount = 0
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        val output = ArrayList<Byte>((values.size * fromBits + toBits - 1) / toBits)
        for (value in values) {
            if (value < 0 || (value ushr fromBits) != 0) return null
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bitCount += fromBits
            while (bitCount >= toBits) {
                bitCount -= toBits
                output += ((accumulator ushr bitCount) and maxValue).toByte()
            }
        }
        if (pad) {
            if (bitCount > 0) output += ((accumulator shl (toBits - bitCount)) and maxValue).toByte()
        } else if (bitCount >= fromBits || ((accumulator shl (toBits - bitCount)) and maxValue) != 0) {
            return null
        }
        return output.toByteArray()
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.length !in 3..253 || '.' !in domain) return false
        return domain.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun doubleSha256(value: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(value))
    }

    private fun String.removeScheme(scheme: String): String =
        if (startsWith(scheme, ignoreCase = true)) substring(scheme.length) else this

    private val LUD16_USERNAME_CHARS = ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '_', '.', '+')
}
