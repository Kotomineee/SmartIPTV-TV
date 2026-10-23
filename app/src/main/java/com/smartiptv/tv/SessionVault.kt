package com.smartiptv.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(context: Context, private val keyProvider: () -> SecretKey = ::androidKey) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    val lastServer: String get() = prefs.getString("last_server", "") ?: ""
    val lastUser: String get() = prefs.getString("last_user", "") ?: ""
    fun remember(credentials: Credentials, enabled: Boolean) {
        prefs.edit().putString("last_server", credentials.server).putString("last_user", credentials.username).apply()
        if (!enabled) { clear(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val data = JSONObject().put("server", credentials.server).put("username", credentials.username).put("password", credentials.password).toString().toByteArray()
        val encrypted = cipher.doFinal(data)
        val value = "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString("credentials", value).commit()) { "Não foi possível salvar o acesso neste aparelho." }
    }
    fun restore(): Credentials? {
        val raw = prefs.getString("credentials", null) ?: return null
        try {
            val parts = raw.split(':')
            require(parts.size == 3 && parts[0] == "v1")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            val data = JSONObject(String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8))
            return Credentials.validated(data.getString("server"), data.getString("username"), data.getString("password"))
        } catch (_: Exception) {
            clear()
            throw ProviderFailure("Não foi possível recuperar o acesso salvo. Entre novamente.")
        }
    }
    fun clear() { prefs.edit().remove("credentials").commit() }
}

private fun androidKey(): SecretKey {
    val alias = "SmartIPTV.session.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}
