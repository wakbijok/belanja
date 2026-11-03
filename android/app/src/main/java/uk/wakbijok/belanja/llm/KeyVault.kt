package uk.wakbijok.belanja.llm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the API key with an AES-GCM key that lives in the AndroidKeyStore and
 * cannot be exported — not by us, not by a backup agent, not by `run-as`.
 *
 * Why this and not EncryptedSharedPreferences: the key must NOT ride along
 * with the SAF backup tree. Receipts are mirrored to Nextcloud by design; an
 * API key landing in that folder would be a credential leak wearing a backup's
 * clothes. Storing only ciphertext in DataStore means the copy that reaches
 * the backup is inert — the KeyStore half never leaves the phone.
 *
 * Consequence, deliberate: a restore onto a different device (or after the app
 * data is wiped) cannot decrypt the old blob. [unseal] returns "" and the user
 * is asked for the key again. Losing a re-pasteable key beats leaking it.
 */
object KeyVault {

    private const val ALIAS = "belanja_llm_key_v1"
    private const val STORE = "AndroidKeyStore"
    private const val XFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Base64 of iv || ciphertext. Blank in, blank out. */
    fun seal(plain: String): String {
        if (plain.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(XFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    /** Returns "" when the blob is missing, truncated, or sealed by a key we no longer hold. */
    fun unseal(sealed: String): String {
        if (sealed.isBlank()) return ""
        return runCatching {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance(XFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** Drops the KeyStore entry, which makes every existing blob undecryptable. */
    fun destroy() {
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }
}
