package com.shiguang.album.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码加密存储。
 *
 * - Android 6.0+（API 23）：使用 Android Keystore 里的 AES 密钥，密钥不出安全硬件（有 TEE 时）。
 * - Android 5.x（API 21/22）：Keystore 不支持 AES，退化为「应用私有随机密钥 + AES/GCM」，
 *   密钥放在应用私有 SharedPreferences 里，能防止配置文件被单独拷走后泄露密码。
 *
 * 存储格式：Base64( IV(12B) + 密文 + GCM Tag )
 */
object SecretCrypto {

    private const val KEY_ALIAS = "shiguang_master_key"
    private const val PREFS = "shiguang_secure"
    private const val PREF_FALLBACK_KEY = "fallback_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(context: Context, plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(context))
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(context: Context, encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_SIZE) return ""
            val iv = raw.copyOfRange(0, IV_SIZE)
            val body = raw.copyOfRange(IV_SIZE, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(context), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(context: Context): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreKey() else fallbackKey(context)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun fallbackKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_FALLBACK_KEY, null)
        if (stored != null) {
            return SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_FALLBACK_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return SecretKeySpec(bytes, "AES")
    }
}
