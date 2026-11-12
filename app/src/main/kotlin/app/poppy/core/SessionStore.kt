package app.poppy.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A persisted account: the Matrix session plus where its encrypted stores live. */
data class StoredAccount(
    val session: Session,
    val dataPath: String,
    val cachePath: String,
    /** Random passphrase for the SDK's encrypted SQLite stores (crypto, state, event cache). */
    val storePassphrase: String,
)

/**
 * Stores accounts in a single file encrypted with an AES-256-GCM key that never leaves the
 * Android Keystore. Plain-text tokens are never written to disk.
 */
class SessionStore(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "accounts.bin")
    private val lock = Any()

    fun load(): List<StoredAccount> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONArray(String(decrypt(file.readBytes()), Charsets.UTF_8))
            (0 until json.length()).map { fromJson(json.getJSONObject(it)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun active(): StoredAccount? = load().firstOrNull()

    fun save(account: StoredAccount) = synchronized(lock) {
        val others = load().filterNot { it.session.userId == account.session.userId }
        write(listOf(account) + others)
    }

    fun updateSession(session: Session) = synchronized(lock) {
        val accounts = load()
        val updated = accounts.map { if (it.session.userId == session.userId) it.copy(session = session) else it }
        write(updated)
    }

    fun find(userId: String): StoredAccount? = load().firstOrNull { it.session.userId == userId }

    fun remove(userId: String) = synchronized(lock) {
        write(load().filterNot { it.session.userId == userId })
    }

    fun newPaths(): Pair<String, String> {
        val id = java.util.UUID.randomUUID().toString()
        val data = File(context.filesDir, "sessions/$id").apply { mkdirs() }
        val cache = File(context.cacheDir, "sessions/$id").apply { mkdirs() }
        return data.absolutePath to cache.absolutePath
    }

    fun newPassphrase(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun write(accounts: List<StoredAccount>) {
        val json = JSONArray().apply { accounts.forEach { put(toJson(it)) } }
        val tmp = File(file.parentFile, "accounts.tmp")
        tmp.writeBytes(encrypt(json.toString().toByteArray(Charsets.UTF_8)))
        tmp.renameTo(file)
    }

    private fun toJson(a: StoredAccount) = JSONObject().apply {
        put("accessToken", a.session.accessToken)
        put("refreshToken", a.session.refreshToken)
        put("userId", a.session.userId)
        put("deviceId", a.session.deviceId)
        put("homeserverUrl", a.session.homeserverUrl)
        put("oauthData", a.session.oauthData)
        put("slidingSync", a.session.slidingSyncVersion.name)
        put("dataPath", a.dataPath)
        put("cachePath", a.cachePath)
        put("passphrase", a.storePassphrase)
    }

    private fun fromJson(o: JSONObject) = StoredAccount(
        session = Session(
            accessToken = o.getString("accessToken"),
            refreshToken = o.optStringOrNull("refreshToken"),
            userId = o.getString("userId"),
            deviceId = o.getString("deviceId"),
            homeserverUrl = o.getString("homeserverUrl"),
            oauthData = o.optStringOrNull("oauthData"),
            slidingSyncVersion = runCatching { SlidingSyncVersion.valueOf(o.getString("slidingSync")) }
                .getOrDefault(SlidingSyncVersion.NATIVE),
        ),
        dataPath = o.getString("dataPath"),
        cachePath = o.getString("cachePath"),
        storePassphrase = o.getString("passphrase"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key)

    // ---- Keystore crypto ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }

    private companion object {
        const val KEY_ALIAS = "poppy_accounts_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
