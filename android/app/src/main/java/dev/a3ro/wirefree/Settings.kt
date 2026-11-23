package dev.a3ro.wirefree

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class Pairing(val host: String, val ip: String, val name: String, val key: ByteArray) {
    companion object {
        fun parse(text: String): Pairing {
            val uri = URI(text.trim())
            require(uri.scheme == "wirefree" && uri.rawAuthority == "pair" &&
                uri.rawPath.isEmpty() && uri.rawFragment == null)
            val fields = mutableMapOf<String, String>()
            for (part in requireNotNull(uri.rawQuery).split('&')) {
                val (name, value) = part.split('=', limit = 2)
                require(fields.put(name, URLDecoder.decode(value, "UTF-8")) == null)
            }
            require(fields.keys == setOf("v", "h", "ip", "p", "n", "k"))
            require(fields["v"] == "1" && fields["p"] == PORT.toString())
            val host = fields.getValue("h")
            require(host.length in 1..253 && host.removeSuffix(".").split('.').all {
                it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
            })
            val ip = fields.getValue("ip")
            val parts = ip.split('.')
            require(parts.size == 4 && parts.all { part ->
                part.toIntOrNull()?.let { it in 0..255 && it.toString() == part } == true
            } && parts[0] == "100" && parts[1].toInt() in 64..127)
            val name = fields.getValue("n")
            require(name.isNotBlank() && name.none(Char::isISOControl))
            val encoded = fields.getValue("k")
            require(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
            val key = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            require(Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) == encoded)
            return Pairing(host, ip, name, key)
        }
    }
}

internal class Settings(context: Context) {
    val prefs = context.getSharedPreferences("wirefree", Context.MODE_PRIVATE)
    val name get() = prefs.getString("name", null)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) { prefs.edit().putBoolean("muted", value).apply() }

    fun save(pairing: Pairing) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.iv + cipher.doFinal(pairing.key)
        check(prefs.edit().putString("host", pairing.host).putString("ip", pairing.ip)
            .putString("name", pairing.name)
            .putString("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit())
    }

    fun load(): Pairing? = runCatching {
        val sealed = Base64.decode(prefs.getString("sealed", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, sealed, 0, 12))
        val secret = cipher.doFinal(sealed, 12, sealed.size - 12)
        check(secret.size == 32)
        Pairing(prefs.getString("host", null)!!, prefs.getString("ip", null)!!, name!!, secret)
    }.getOrNull()

    fun forget() {
        prefs.edit().remove("host").remove("ip").remove("name").remove("sealed")
            .putBoolean("enabled", false).commit()
        runCatching { store().deleteEntry(ALIAS) }
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        val existing = store().getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object { const val ALIAS = "wirefree-pairing-v1" }
}
