package app.poppy.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * Self-test run when a homeserver can't be reached. It walks the same steps the Rust SDK takes
 * (DNS → HTTPS → certificate verification through rustls-platform-verifier) using Android's own
 * stack, so a failure report says exactly which step breaks. Only the user's own server is contacted.
 */
object ConnectionDiagnostics {

    suspend fun run(context: Context, serverInput: String): String = withContext(Dispatchers.IO) {
        val host = serverInput.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        val out = StringBuilder("Connection test for $host\n")

        // 1. DNS
        val addresses = runCatching { InetAddress.getAllByName(host).map { it.hostAddress } }
        out.append("DNS: ").append(addresses.fold({ it.joinToString() }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" })).append('\n')
        if (addresses.isFailure) return@withContext out.toString()

        // 2. HTTPS with the platform trust store
        var chain: Array<X509Certificate>? = null
        try {
            val conn = (URL("https://$host/.well-known/matrix/client").openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            chain = conn.serverCertificates.filterIsInstance<X509Certificate>().toTypedArray()
            out.append("HTTPS (Android): HTTP $code, TLS ${conn.cipherSuite}\n")
            conn.disconnect()
        } catch (t: Throwable) {
            out.append("HTTPS (Android): FAILED ${t.javaClass.simpleName}: ${t.message}\n")
            t.cause?.let { out.append("  cause: ${it.javaClass.simpleName}: ${it.message}\n") }
        }

        // 3. The exact verifier the Rust SDK calls over JNI
        chain?.let { certs ->
            out.append("Chain (${certs.size}):\n")
            certs.forEach { c -> out.append("  ${c.subjectX500Principal.name} <- ${c.issuerX500Principal.name} (${c.publicKey.algorithm})\n") }
            out.append(verify(context, host, certs))
        }
        out.toString()
    }

    private fun verify(context: Context, host: String, chain: Array<X509Certificate>): String = try {
        val cls = Class.forName("org.rustls.platformverifier.CertificateVerifier", true, context.classLoader)
        val method = cls.declaredMethods.first { it.name == "verifyCertificateChain" }.apply { isAccessible = true }
        val der = chain.map { it.encoded }.toTypedArray()
        val serverAuth = arrayOf("1.3.6.1.5.5.7.3.1")
        buildString {
            for (authType in listOf("RSA", "ECDHE_ECDSA", "GENERIC")) {
                val result = method.invoke(null, context, host, authType, serverAuth, null, System.currentTimeMillis(), der)
                val codeField = result.javaClass.getDeclaredField("code").apply { isAccessible = true }
                val msgField = result.javaClass.getDeclaredField("message").apply { isAccessible = true }
                append("Verifier[$authType]: code=${codeField.get(result)} ${msgField.get(result) ?: ""}\n")
            }
        }
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        "Verifier: FAILED ${cause.javaClass.simpleName}: ${cause.message}\n"
    }
}
