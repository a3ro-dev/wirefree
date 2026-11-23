package dev.a3ro.wirefree

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import org.json.JSONObject

internal data class LaptopHello(val ts: Long, val wantMic: Boolean, val wantSpk: Boolean)

internal fun laptopHello(payload: ByteArray): LaptopHello? {
    val fields = readObject(payload) ?: return null
    if (fields["role"] != "laptop" || fields["name"] !is String || fields["app"] !is String) return null
    return LaptopHello(fields["ts"] as? Long ?: return null,
        fields["wantMic"] as? Boolean ?: return null, fields["wantSpk"] as? Boolean ?: return null)
}

internal fun command(payload: ByteArray) =
    readObject(payload)?.takeIf { it.size == 1 }?.get("cmd") as? String

internal fun phoneHello(name: String, status: Status, muted: Boolean) = JSONObject()
    .put("ts", System.currentTimeMillis())
    .put("role", "phone")
    .put("name", name)
    .put("earpods", status.earpods)
    .put("muted", muted)
    .put("busy", status.priority != null)
    .put("micLive", status.mic != null)
    .put("app", "1.0.0")
    .toString().encodeToByteArray()

private fun readObject(payload: ByteArray): Map<String, Any>? = runCatching {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
    JsonReader(StringReader(text)).use { reader ->
        reader.beginObject()
        val result = mutableMapOf<String, Any>()
        while (reader.hasNext()) {
            val name = reader.nextName()
            val value = when (reader.peek()) {
                JsonToken.STRING -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NUMBER -> requireNotNull(reader.nextString().toLongOrNull())
                else -> error("Invalid value")
            }
            check(result.put(name, value) == null)
        }
        reader.endObject()
        check(reader.peek() == JsonToken.END_DOCUMENT)
        result
    }
}.getOrNull()
