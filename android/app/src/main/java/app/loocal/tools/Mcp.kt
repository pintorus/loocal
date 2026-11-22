package app.loocal.tools

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class McpTool(val name: String, val description: String, /** The tool's JSON input schema, as text. */ val schema: String)

@Serializable
data class McpServer(val id: String = java.util.UUID.randomUUID().toString(), val url: String, val name: String, val tools: List<McpTool>) {
    val connectorId get() = "mcp:$id"
}

/** One tool as the model sees it, with what's needed to call it. */
data class ToolDefinition(val url: String, val token: String?, val tool: McpTool)

/**
 * Minimal MCP client over the Streamable HTTP transport (port of iOS `MCPClient`): JSON-RPC
 * requests whose replies arrive as JSON or as a short server-sent-event stream.
 */
class McpClient(private val url: String, private val token: String?) {
    private var sessionId: String? = null
    private var nextId = 1

    /** Handshake, then list tools. Returns the server's name and its tools. */
    suspend fun discover(): Pair<String, List<McpTool>> = withContext(Dispatchers.IO) {
        val info = handshake()
        val listed = call("tools/list", JSONObject())
        val array = listed.optJSONArray("tools") ?: JSONArray()
        val tools = (0 until array.length()).mapNotNull { i ->
            val t = array.getJSONObject(i)
            val name = t.optString("name").ifEmpty { return@mapNotNull null }
            McpTool(name, t.optString("description"), t.optJSONObject("inputSchema")?.toString() ?: "{}")
        }
        info to tools
    }

    /** Runs one tool and returns its text output. */
    suspend fun run(tool: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        handshake()
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        val result = call("tools/call", JSONObject().put("name", tool).put("arguments", arguments))
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = (0 until content.length()).mapNotNull { content.getJSONObject(it).optString("text").takeIf { s -> s.isNotEmpty() } }.joinToString("\n")
        if (result.optBoolean("isError")) "Tool error: $text" else text.ifEmpty { "(no output)" }
    }

    private fun handshake(): String {
        val result = call(
            "initialize",
            JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Loocal").put("version", "1.0")),
        )
        send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        return result.optJSONObject("serverInfo")?.optString("name")?.takeIf { it.isNotEmpty() } ?: java.net.URL(url).host ?: "MCP server"
    }

    private fun call(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val messages = send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        val reply = messages.firstOrNull { it.optInt("id", -1) == id } ?: messages.firstOrNull() ?: error("The MCP server didn’t answer $method.")
        reply.optJSONObject("error")?.let { error("MCP: ${it.optString("message", "request failed")}") }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private fun send(body: JSONObject): List<JSONObject> {
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("MCP-Protocol-Version", "2025-06-18")
            sessionId?.let { put("Mcp-Session-Id", it) }
            if (!token.isNullOrEmpty()) put("Authorization", "Bearer $token")
        }
        val (connection, text) = try {
            Http.request(url, "POST", body.toString(), "application/json", headers, 20_000)
        } catch (e: Http.HttpError) {
            error(if (e.code == 401) "MCP: the server needs a valid token." else "MCP: server returned ${e.code}.")
        }
        connection.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        if (text.isBlank()) return emptyList()
        if (connection.contentType?.contains("text/event-stream") == true) {
            return text.lines().filter { it.startsWith("data:") }.mapNotNull { runCatching { JSONObject(it.drop(5).trim()) }.getOrNull() }
        }
        return runCatching { val a = JSONArray(text); (0 until a.length()).map { a.getJSONObject(it) } }.getOrElse { listOf(JSONObject(text)) }
    }
}

/** Parses a Hermes-style `<tool_call>{"name": …, "arguments": {…}}</tool_call>` block. */
object ToolCall {
    const val OPEN = "<tool_call>"

    fun parse(text: String): Pair<String, String>? {
        val start = text.indexOf(OPEN).takeIf { it >= 0 } ?: return null
        var body = text.substring(start + OPEN.length)
        body.indexOf("</tool_call>").takeIf { it >= 0 }?.let { body = body.substring(0, it) }
        val o = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        val name = o.optString("name").ifEmpty { return null }
        val args = when (val a = o.opt("arguments")) { is JSONObject -> a.toString(); is String -> a; else -> "{}" }
        return name to args
    }

    /** The part of the text that should be shown: everything before a tool call starts. */
    fun visible(text: String) = text.substringBefore(OPEN)

    /** Tool specs described in text (llama.cpp's built-in templates don't take tool specs). */
    fun describe(tools: List<ToolDefinition>): String =
        "You can call tools. To call one, reply with only <tool_call>{\"name\": \"…\", \"arguments\": {…}}</tool_call> and wait for the result.\nTools:\n" +
            tools.joinToString("\n") { "- ${it.tool.name}: ${it.tool.description.take(160)} Arguments schema: ${it.tool.schema.take(260)}" }
}

/**
 * MCP bearer tokens, encrypted with a key kept in the Android Keystore (the iOS Keychain's role),
 * never stored in plain preferences.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun set(account: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(account).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(account, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun get(account: String): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(account, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { const val ALIAS = "loocal.mcp" }
}
