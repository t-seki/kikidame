package dev.tseki.jellyfinradio.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** アクセストークンの暗号化。実装は Android Keystore、テストではフェイク。 */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}

/**
 * Android Keystore に生成した AES-256 鍵で AES-GCM 暗号化する。鍵は端末外に出ず、
 * 保存形式は `base64(iv) : base64(ciphertext)`。`EncryptedSharedPreferences` は使わない（非推奨）。
 */
@Singleton
class KeystoreTokenCipher @Inject constructor() : TokenCipher {

    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String {
        val (iv, body) = stored.split(SEPARATOR, limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "session-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
