package org.rustls.platformverifier

import android.content.Context
import android.net.http.X509TrustManagerExtensions
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXRevocationChecker
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/*
 * Poppy's implementation of the Android half of rustls-platform-verifier, which the Matrix Rust
 * SDK calls over JNI for every TLS handshake. It replaces the upstream AAR (0.2.0) and keeps the
 * exact JNI contract: class/method names, the method descriptor, StatusCode values and the
 * VerificationResult field names.
 *
 * Why: upstream reports *any* revocation-check exception as "Revoked". Let's Encrypt certificates
 * no longer carry an OCSP URL (2025+), so Android throws "Certificate does not specify OCSP
 * responder" and every connection to a Let's Encrypt server failed. Here a certificate is only
 * rejected as revoked when Android positively says it is revoked; an undeterminable status is a
 * soft failure, matching upstream's stated SOFT_FAIL intent.
 *
 * Hostname verification is done on the Rust side (webpki), as with upstream.
 */

/** Must match the integer values the native side expects. */
enum class StatusCode(val value: Int) {
    Ok(0),
    Unavailable(1),
    Expired(2),
    UnknownCert(3),
    Revoked(4),
    InvalidEncoding(5),
    InvalidExtension(6),
}

/** Read from native code: the `code` int field and `getMessage()` (both kept for safety). */
class VerificationResult(status: StatusCode, @JvmField val message: String? = null) {
    @JvmField val code: Int = status.value

    fun getMessage(): String? = message
}

object CertificateVerifier {
    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    /** System + user-installed CAs, as upstream does, so private-CA homeservers keep working. */
    private val systemKeystore: KeyStore? = runCatching {
        KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    }.getOrNull()

    private val trustManager: X509TrustManagerExtensions? by lazy {
        runCatching {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(systemKeystore)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            X509TrustManagerExtensions(tm)
        }.getOrNull()
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER", "unused")
    private fun verifyCertificateChain(
        context: Context,
        serverName: String,
        authMethod: String,
        allowedEkus: Array<String>,
        ocspResponse: ByteArray?,
        time: Long,
        certChain: Array<ByteArray>,
    ): VerificationResult {
        // 1. Parse
        val chain = try {
            certChain.map { certFactory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        } catch (e: Exception) {
            return VerificationResult(StatusCode.InvalidEncoding, e.toString())
        }
        if (chain.isEmpty()) return VerificationResult(StatusCode.InvalidEncoding, "empty certificate chain")
        val leaf = chain[0]

        // 2. Validity at the time rustls asked about
        try {
            leaf.checkValidity(Date(time))
        } catch (e: CertificateExpiredException) {
            return VerificationResult(StatusCode.Expired, e.toString())
        } catch (e: CertificateNotYetValidException) {
            return VerificationResult(StatusCode.Expired, e.toString())
        }

        // 3. Extended key usage (e.g. serverAuth)
        try {
            val ekus = leaf.extendedKeyUsage
            if (!ekus.isNullOrEmpty() && ekus.none { it in allowedEkus }) {
                return VerificationResult(StatusCode.InvalidExtension, "certificate is not valid for TLS server authentication")
            }
        } catch (e: CertificateException) {
            return VerificationResult(StatusCode.InvalidExtension, e.toString())
        }

        // 4. Path building & trust against Android's trust store (incl. network security config)
        val tm = trustManager ?: return VerificationResult(StatusCode.Unavailable, "Android trust manager unavailable")
        val validated: List<X509Certificate> = try {
            tm.checkServerTrusted(chain.toTypedArray(), authMethod, serverName)
        } catch (e: CertificateException) {
            return VerificationResult(StatusCode.UnknownCert, e.toString())
        }

        // 5. Revocation: hard-fail only on a definitive "revoked" answer.
        checkRevocation(validated, ocspResponse)?.let { return it }

        return VerificationResult(StatusCode.Ok)
    }

    private fun checkRevocation(validated: List<X509Certificate>, ocspResponse: ByteArray?): VerificationResult? {
        val keystore = systemKeystore ?: return null
        if (validated.size < 2) return null
        val leaf = validated[0]
        val hasStapled = ocspResponse != null && ocspResponse.isNotEmpty()
        // Without a stapled response or an OCSP URL there is nothing Android can check online.
        if (!hasStapled && !hasOcspUrl(leaf)) return null
        return try {
            val validator = CertPathValidator.getInstance("PKIX")
            val checker = (validator.revocationChecker as PKIXRevocationChecker).apply {
                options = setOf(PKIXRevocationChecker.Option.SOFT_FAIL, PKIXRevocationChecker.Option.ONLY_END_ENTITY)
                if (hasStapled) ocspResponses = mapOf(leaf to ocspResponse!!)
            }
            val params = PKIXBuilderParameters(keystore, X509CertSelector()).apply {
                addCertPathChecker(checker)
                isRevocationEnabled = false
            }
            // Validate the path without the trust anchor itself.
            validator.validate(certFactory.generateCertPath(validated.dropLast(1)), params)
            null
        } catch (e: CertPathValidatorException) {
            if (e.reason == CertPathValidatorException.BasicReason.REVOKED) {
                VerificationResult(StatusCode.Revoked, e.toString())
            } else {
                null // undetermined → soft failure
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Authority Information Access (1.3.6.1.5.5.7.1.1) containing an OCSP access method. */
    private fun hasOcspUrl(cert: X509Certificate): Boolean {
        val aia = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return false
        // id-ad-ocsp = 1.3.6.1.5.5.7.48.1 → DER 06 08 2B 06 01 05 05 07 30 01
        val ocspOid = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01)
        return aia.indexOf(ocspOid) >= 0
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
