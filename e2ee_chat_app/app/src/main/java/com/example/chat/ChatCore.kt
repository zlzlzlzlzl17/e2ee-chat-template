package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ChatUser(
    val userCode: String = "",
    val username: String,
    val color: String,
    val avatarUrl: String = "",
    val isAdmin: Boolean,
)

data class ConversationSummary(
    val id: Long,
    val kind: String,
    val slug: String,
    val title: String,
    val avatarUrl: String,
    val directUsername: String,
    val lastMessageTs: Long,
    val lastMessagePreview: String,
    val unreadCount: Int,
    val lastReadMessageId: Long,
)

data class ConversationReadState(
    val userCode: String = "",
    val username: String,
    val lastReadMessageId: Long,
)

data class ConversationDeliveryState(
    val userCode: String = "",
    val username: String,
    val lastDeliveredMessageId: Long,
)

data class CallConfig(
    val iceServers: List<CallIceServerConfig>,
)

data class PendingIncomingCallInvite(
    val conversationId: Long,
    val peerUserCode: String = "",
    val peerUsername: String,
    val createdAt: Long,
)

enum class AppReleaseChannel(val wireValue: String) {
    STABLE("stable"),
    PRERELEASE("prerelease");

    companion object {
        fun fromWire(value: String?): AppReleaseChannel =
            entries.firstOrNull { it.wireValue.equals(value.orEmpty(), ignoreCase = true) } ?: STABLE
    }
}

data class AppReleaseInfo(
    val version: String,
    val fileName: String,
    val originalName: String,
    val fileSize: Long,
    val uploadedAt: Long,
    val downloadUrl: String,
    val channel: AppReleaseChannel = AppReleaseChannel.STABLE,
    val versionLabel: String = version,
)

data class ReplyPreview(val id: Long, val username: String, val color: String, val preview: String)

enum class LocalSendState {
    SENT,
    SENDING,
    FAILED,
}

data class ChatMessage(
    val id: Long,
    val conversationId: Long,
    val ts: Long,
    val expiresAt: Long,
    val userCode: String = "",
    val username: String,
    val color: String,
    val kind: String,
    val payload: String,
    val e2ee: Boolean,
    val replyTo: ReplyPreview?,
    val mentions: List<String>,
    val localSendState: LocalSendState = LocalSendState.SENT,
    val localAttachmentPath: String = "",
    val localAttachmentName: String = "",
    val localAttachmentMime: String = "",
    val localAttachmentSize: Long = 0L,
    val localAttachmentDurationMs: Long = 0L,
)

data class DecryptedAttachment(
    val name: String,
    val mime: String,
    val size: Long,
    val file: File? = null,
    val uri: Uri? = null,
)

data class LoginResult(
    val token: String,
    val userCode: String = "",
    val username: String,
    val color: String,
    val avatarUrl: String,
    val isAdmin: Boolean,
)

data class HistoryPage(val items: List<ChatMessage>, val hasMore: Boolean)

data class TransferProgress(
    val label: String,
    val progress: Float,
    val bytesDone: Long,
    val totalBytes: Long,
)

const val DEFAULT_SERVER_URL = "https://chat.example.com"
const val APP_CLIENT_HEADER_NAME = "X-E2EE-Chat-Client"
const val APP_CLIENT_HEADER_VALUE = "android-app"

class ChatPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("e2ee_chat_prefs", Context.MODE_PRIVATE)
    private val pendingIncomingCallKey = "pending_incoming_call"

    private fun audioHeardKey(userIdentity: String, messageId: Long): String =
        "audio_heard_${userIdentity}_${messageId}"

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var savedLoginUsername: String
        get() = prefs.getString("saved_login_username", "") ?: ""
        set(value) = prefs.edit().putString("saved_login_username", value).apply()

    var savedLoginPassword: String
        get() = prefs.getString("saved_login_password", "") ?: ""
        set(value) = prefs.edit().putString("saved_login_password", value).apply()

    var roomSecret: String
        get() = prefs.getString("room_secret", "") ?: ""
        set(value) = prefs.edit().putString("room_secret", value).apply()

    var e2eeEnabled: Boolean
        get() = prefs.getBoolean("e2ee_enabled", true)
        set(value) = prefs.edit().putBoolean("e2ee_enabled", value).apply()

    var language: String
        get() = prefs.getString("language", AppLanguage.systemDefault().name) ?: AppLanguage.systemDefault().name
        set(value) = prefs.edit().putString("language", value).apply()

    var displayMode: String
        get() = prefs.getString("display_mode", AppDisplayMode.SYSTEM.name) ?: AppDisplayMode.SYSTEM.name
        set(value) = prefs.edit().putString("display_mode", value).apply()

    var dynamicColorsEnabled: Boolean
        get() = prefs.getBoolean("dynamic_colors_enabled", true)
        set(value) = prefs.edit().putBoolean("dynamic_colors_enabled", value).apply()

    var lastNotifiedMessageId: Long
        get() = prefs.getLong("last_notified_message_id", 0L)
        set(value) = prefs.edit().putLong("last_notified_message_id", value).apply()

    fun hasHeardAudioMessage(userIdentity: String, messageId: Long): Boolean {
        if (userIdentity.isBlank() || messageId <= 0L) return false
        return prefs.getBoolean(audioHeardKey(userIdentity, messageId), false)
    }

    fun markAudioMessageHeard(userIdentity: String, messageId: Long) {
        if (userIdentity.isBlank() || messageId <= 0L) return
        prefs.edit().putBoolean(audioHeardKey(userIdentity, messageId), true).apply()
    }

    fun saveLogin(username: String, password: String) {
        prefs.edit()
            .putString("saved_login_username", username)
            .putString("saved_login_password", password)
            .apply()
    }

    fun clearSession() {
        prefs.edit().remove("token").remove("username").apply()
    }

    fun clearSavedLogin() {
        prefs.edit()
            .remove("saved_login_username")
            .remove("saved_login_password")
            .apply()
    }

    fun pendingIncomingCall(): PendingIncomingCallInvite? {
        val raw = prefs.getString(pendingIncomingCallKey, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PendingIncomingCallInvite(
                conversationId = json.optLong("conversation_id", 0L),
                peerUserCode = json.optString("peer_user_code"),
                peerUsername = json.optString("peer_username"),
                createdAt = json.optLong("created_at", 0L),
            )
        }.getOrNull()?.takeIf { it.conversationId > 0L && it.peerUsername.isNotBlank() }
    }

    fun setPendingIncomingCall(invite: PendingIncomingCallInvite?) {
        if (invite == null) {
            prefs.edit().remove(pendingIncomingCallKey).apply()
            return
        }
        val value = JSONObject()
            .put("conversation_id", invite.conversationId)
            .put("peer_user_code", invite.peerUserCode)
            .put("peer_username", invite.peerUsername)
            .put("created_at", invite.createdAt)
            .toString()
        prefs.edit().putString(pendingIncomingCallKey, value).apply()
    }
}

object ChatCrypto {
    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun deriveKey(secret: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(secret.toCharArray(), salt, 200_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encryptText(secret: String, plaintext: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = JSONObject()
            .put("v", 1)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ct", b64(encrypted))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptText(secret: String, payload: String): String {
        val json = JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(secret, fromB64(json.getString("salt"))),
            GCMParameterSpec(128, fromB64(json.getString("iv")))
        )
        return String(cipher.doFinal(fromB64(json.getString("ct"))), StandardCharsets.UTF_8)
    }

    fun encryptBinary(secret: String, plainBytes: ByteArray): Triple<ByteArray, String, String> {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret, salt), GCMParameterSpec(128, iv))
        return Triple(cipher.doFinal(plainBytes), b64(salt), b64(iv))
    }

    fun decryptBinary(secret: String, salt: String, iv: String, cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(secret, fromB64(salt)),
            GCMParameterSpec(128, fromB64(iv))
        )
        return cipher.doFinal(cipherBytes)
    }
}

private class ProgressRequestBody(
    private val body: RequestBody,
    private val onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)?,
) : RequestBody() {
    override fun contentType() = body.contentType()

    override fun contentLength() = body.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val total = buffer.size
        var written = 0L
        while (!buffer.exhausted()) {
            val toWrite = minOf(buffer.size, 8_192L)
            sink.write(buffer, toWrite)
            written += toWrite
            onProgress?.invoke(written, total)
        }
    }
}

class ChatApi {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun base(url: String): String = url.trim().trimEnd('/')

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)

    private fun authRequest(url: String, token: String) =
        requestBuilder(url).header("Authorization", "Bearer $token")

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IllegalStateException(parseError(response))
        return response
    }

    private fun parseError(response: Response): String {
        val text = response.body?.string().orEmpty()
        return runCatching { JSONObject(text).optString("error").ifBlank { response.message } }
            .getOrDefault(response.message.ifBlank { "request_failed" })
    }

    fun login(serverUrl: String, username: String, password: String): LoginResult {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val request = requestBuilder("${base(serverUrl)}/api/login")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return LoginResult(
                token = json.getString("token"),
                userCode = json.optString("user_code"),
                username = json.getString("username"),
                color = json.getString("color"),
                avatarUrl = json.optString("avatar_url"),
                isAdmin = json.optBoolean("is_admin", false)
            )
        }
    }

    fun me(serverUrl: String, token: String): ChatUser {
        execute(authRequest("${base(serverUrl)}/api/me", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            return ChatUser(
                userCode = json.optString("user_code"),
                username = json.getString("username"),
                color = json.getString("color"),
                avatarUrl = json.optString("avatar_url"),
                isAdmin = json.optBoolean("is_admin")
            )
        }
    }

    fun changeUsername(serverUrl: String, token: String, username: String): ChatUser {
        val body = JSONObject().put("username", username).toString()
        val request = authRequest("${base(serverUrl)}/api/username", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val user = JSONObject(response.body!!.string()).getJSONObject("user")
            return ChatUser(
                userCode = user.optString("user_code"),
                username = user.getString("username"),
                color = user.getString("color"),
                avatarUrl = user.optString("avatar_url"),
                isAdmin = user.optBoolean("is_admin", false)
            )
        }
    }

    fun users(serverUrl: String, token: String): List<ChatUser> =
        parseUsers(execute(authRequest("${base(serverUrl)}/api/users", token).get().build()).body!!.string())

    fun conversations(serverUrl: String, token: String): List<ConversationSummary> {
        execute(authRequest("${base(serverUrl)}/api/conversations", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ConversationSummary(
                    id = item.getLong("id"),
                    kind = item.optString("kind"),
                    slug = item.optString("slug"),
                    title = item.optString("title"),
                    avatarUrl = item.optString("avatar_url"),
                    directUsername = item.optString("direct_username"),
                    lastMessageTs = item.optLong("last_message_ts", 0L),
                    lastMessagePreview = item.optString("last_message_preview"),
                    unreadCount = item.optInt("unread_count", 0),
                    lastReadMessageId = item.optLong("last_read_message_id", 0L)
                )
            }
        }
    }

    fun callConfig(serverUrl: String, token: String): CallConfig {
        execute(authRequest("${base(serverUrl)}/api/call_config", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return CallConfig(
                iceServers = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    val urlsJson = item.optJSONArray("urls")
                    val urls = if (urlsJson != null) {
                        List(urlsJson.length()) { urlsJson.optString(it) }.filter { it.isNotBlank() }
                    } else {
                        listOf(item.optString("url")).filter { it.isNotBlank() }
                    }
                    CallIceServerConfig(
                        urls = urls,
                        username = item.optString("username"),
                        credential = item.optString("credential")
                    )
                }.filter { it.urls.isNotEmpty() }
            )
        }
    }

    fun appRelease(serverUrl: String, token: String, channel: AppReleaseChannel = AppReleaseChannel.STABLE): AppReleaseInfo? {
        val suffix = if (channel == AppReleaseChannel.STABLE) "" else "?channel=${channel.wireValue}"
        execute(authRequest("${base(serverUrl)}/api/app_release$suffix", token).get().build()).use { response ->
            val item = JSONObject(response.body!!.string()).optJSONObject("item") ?: return null
            return AppReleaseInfo(
                version = item.optString("version"),
                fileName = item.optString("file_name"),
                originalName = item.optString("original_name"),
                fileSize = item.optLong("file_size", 0L),
                uploadedAt = item.optLong("uploaded_at", 0L),
                downloadUrl = item.optString("download_url"),
                channel = AppReleaseChannel.fromWire(item.optString("channel").ifBlank { channel.wireValue }),
                versionLabel = item.optString("version_label").ifBlank { item.optString("version") },
            )
        }
    }

    fun history(
        serverUrl: String,
        token: String,
        conversationId: Long,
        sinceId: Long? = null,
        beforeId: Long? = null,
        limit: Int = 200,
    ): HistoryPage {
        val query = buildList {
            add("conversation_id=$conversationId")
            add("limit=$limit")
            sinceId?.takeIf { it > 0 }?.let { add("since_id=$it") }
            beforeId?.takeIf { it > 0 }?.let { add("before_id=$it") }
        }.joinToString("&")
        val request = authRequest("${base(serverUrl)}/api/history?$query", token).get().build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            val items = json.optJSONArray("items") ?: JSONArray()
            return HistoryPage(
                items = List(items.length()) { parseMessage(items.getJSONObject(it)) },
                hasMore = json.optBoolean("has_more", false)
            )
        }
    }

    fun conversationReadStates(serverUrl: String, token: String, conversationId: Long): List<ConversationReadState> {
        val request = authRequest("${base(serverUrl)}/api/read_states?conversation_id=$conversationId", token).get().build()
        execute(request).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
                return List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    ConversationReadState(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        lastReadMessageId = item.optLong("last_read_message_id", 0L)
                    )
            }
        }
    }

    fun conversationDeliveryStates(serverUrl: String, token: String, conversationId: Long): List<ConversationDeliveryState> {
        val request = authRequest("${base(serverUrl)}/api/delivery_states?conversation_id=$conversationId", token).get().build()
        execute(request).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
                return List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    ConversationDeliveryState(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        lastDeliveredMessageId = item.optLong("last_delivered_message_id", 0L)
                    )
            }
        }
    }

    fun changePassword(serverUrl: String, token: String, newPassword: String) {
        val body = JSONObject().put("new_password", newPassword).toString()
        val request = authRequest("${base(serverUrl)}/api/change_password", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun deleteHistory(serverUrl: String, token: String) {
        val request = authRequest("${base(serverUrl)}/api/delete_history", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun recallMessage(serverUrl: String, token: String, messageId: Long) {
        val request = authRequest("${base(serverUrl)}/api/messages/$messageId/recall", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun uploadAvatar(
        serverUrl: String,
        token: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): String {
        val tempFile = File.createTempFile("avatar_", fileName.substringAfterLast('.', "jpg"))
        tempFile.writeBytes(bytes)
        return try {
            val avatarBody = ProgressRequestBody(tempFile.asRequestBody(mime.toMediaType()), onProgress)
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("avatar", fileName, avatarBody)
                .build()
            val request = authRequest("${base(serverUrl)}/api/avatar", token).post(form).build()
            execute(request).use { response ->
                JSONObject(response.body!!.string()).optString("avatar_url")
            }
        } finally {
            tempFile.delete()
        }
    }

    fun uploadAttachment(
        serverUrl: String,
        token: String,
        conversationId: Long,
        kind: String,
        payloadJson: String,
        replyJson: String,
        encryptedBytes: ByteArray,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): ChatMessage {
        val tempFile = File.createTempFile("upload_", ".enc")
        tempFile.writeBytes(encryptedBytes)
        try {
            val fileBody = ProgressRequestBody(
                tempFile.asRequestBody("application/octet-stream".toMediaType()),
                onProgress
            )
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("conversation_id", conversationId.toString())
                .addFormDataPart("kind", kind)
                .addFormDataPart("payload", payloadJson)
                .addFormDataPart("reply_to", replyJson)
                .addFormDataPart("mentions", "[]")
                .addFormDataPart("attachment", "$kind.enc", fileBody)
                .build()
            val request = authRequest("${base(serverUrl)}/api/upload_attachment", token).post(form).build()
            execute(request).use { response ->
                val json = JSONObject(response.body!!.string())
                return parseMessage(json.getJSONObject("message"))
            }
        } finally {
            tempFile.delete()
        }
    }

    fun connect(serverUrl: String, token: String, listener: WebSocketListener): WebSocket {
        val http = base(serverUrl).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val encoded = URLEncoder.encode(token, "UTF-8")
        return client.newWebSocket(
            requestBuilder("$http/ws?token=$encoded").build(),
            listener
        )
    }

    fun download(
        serverUrl: String,
        path: String,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): ByteArray {
        val absolute = if (path.startsWith("http")) path else "${base(serverUrl)}$path"
        execute(requestBuilder(absolute).get().build()).use { response ->
            val body = response.body ?: return ByteArray(0)
            val total = body.contentLength().coerceAtLeast(0L)
            val source = body.source()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var done = 0L
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                done += read.toLong()
                onProgress?.invoke(done, total)
            }
            return output.toByteArray()
        }
    }

    companion object {
        fun parseUsers(text: String): List<ChatUser> {
            val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ChatUser(
                    userCode = item.optString("user_code"),
                    username = item.getString("username"),
                    color = item.getString("color"),
                    avatarUrl = item.optString("avatar_url"),
                    isAdmin = item.optBoolean("is_admin")
                )
            }
        }

        fun parseMessage(json: JSONObject): ChatMessage {
            val reply = json.optString("reply_to").takeIf { it.isNotBlank() }?.let {
                val obj = JSONObject(it)
                ReplyPreview(
                    id = obj.optLong("id"),
                    username = obj.optString("username"),
                    color = obj.optString("color"),
                    preview = obj.optString("preview")
                )
            }
            val mentionsArray = json.optJSONArray("mentions") ?: JSONArray()
            val mentions = List(mentionsArray.length()) { mentionsArray.optString(it) }
            return ChatMessage(
                id = json.getLong("id"),
                conversationId = json.optLong("conversation_id", 0L),
                ts = json.getLong("ts"),
                expiresAt = json.optLong("expires_at", 0L),
                userCode = json.optString("user_code"),
                username = json.getString("username"),
                color = json.getString("color"),
                kind = json.getString("kind"),
                payload = json.optString("payload"),
                e2ee = json.optInt("e2ee", 0) == 1,
                replyTo = reply,
                mentions = mentions
            )
        }
    }
}

fun resolveAttachment(
    context: Context,
    api: ChatApi,
    serverUrl: String,
    message: ChatMessage,
    secret: String,
    onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    exportToDownloads: Boolean = true,
): DecryptedAttachment {
    val payload = JSONObject(message.payload)
    val attachment = payload.optJSONObject("attachment")
    val isEncryptedAttachment =
        message.e2ee &&
            attachment != null &&
            payload.has("meta") &&
            payload.has("enc")

    val meta = if (isEncryptedAttachment) {
        require(secret.isNotBlank()) { "Room secret required" }
        JSONObject(ChatCrypto.decryptText(secret, payload.getString("meta")))
    } else {
        JSONObject()
            .put("name", payload.optString("name").ifBlank { attachment?.optString("file").orEmpty() })
            .put(
                "mime",
                payload.optString("mime")
                    .ifBlank { attachment?.optString("mime").orEmpty() }
                    .ifBlank { "application/octet-stream" }
            )
            .put("size", payload.optLong("size", 0L))
    }

    val fileName = (meta.optString("name").ifBlank { "${message.kind}.bin" }).replace(Regex("[^A-Za-z0-9._-]"), "_")
    val mime = meta.optString("mime").ifBlank { "application/octet-stream" }
    val cacheFile = attachmentCacheFile(context, message.id, fileName)
    if (cacheFile.exists()) {
        return DecryptedAttachment(
            name = meta.optString("name").ifBlank { fileName },
            mime = mime,
            size = meta.optLong("size", cacheFile.length()),
            file = cacheFile,
            uri = if (exportToDownloads) cachedAttachmentUri(context, message.id, fileName) else null
        )
    }

    val plainBytes = if (isEncryptedAttachment) {
        val encryptedBytes = api.download(serverUrl, attachment!!.getString("url"), onProgress)
        ChatCrypto.decryptBinary(
            secret,
            payload.getJSONObject("enc").getString("salt"),
            payload.getJSONObject("enc").getString("iv"),
            encryptedBytes
        )
    } else {
        val downloadUrl = attachment?.optString("url").orEmpty().ifBlank { payload.optString("url") }
        require(downloadUrl.isNotBlank()) { "Bad attachment" }
        api.download(serverUrl, downloadUrl, onProgress)
    }
    cacheFile.writeBytes(plainBytes)
    val downloadUri = if (exportToDownloads) {
        runCatching {
            ensureAttachmentInDownloads(context, message.id, fileName, mime, plainBytes)
        }.getOrNull()
    } else {
        null
    }
    return DecryptedAttachment(
        name = meta.optString("name").ifBlank { fileName },
        mime = mime,
        size = meta.optLong("size", plainBytes.size.toLong()),
        file = cacheFile,
        uri = downloadUri
    )
}

fun attachmentCacheFile(context: Context, messageId: Long, fileName: String): File {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    return File(sharedDir, "${messageId}_$fileName")
}

private fun attachmentUriMetaFile(context: Context, messageId: Long, fileName: String): File {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    return File(sharedDir, "${messageId}_$fileName.uri")
}

fun cachedAttachmentUri(context: Context, messageId: Long, fileName: String): Uri? {
    val metaFile = attachmentUriMetaFile(context, messageId, fileName)
    if (!metaFile.exists()) return null
    return runCatching { Uri.parse(metaFile.readText()) }.getOrNull()
}

private fun rememberAttachmentUri(context: Context, messageId: Long, fileName: String, uri: Uri) {
    attachmentUriMetaFile(context, messageId, fileName).writeText(uri.toString())
}

fun ensureAttachmentInDownloads(
    context: Context,
    messageId: Long,
    fileName: String,
    mime: String,
    bytes: ByteArray,
): Uri {
    cachedAttachmentUri(context, messageId, fileName)?.let { return it }
    val uri = saveBytesToDownloads(context, fileName, mime, bytes)
    rememberAttachmentUri(context, messageId, fileName, uri)
    return uri
}

fun appUpdateFile(context: Context, fileName: String): File {
    val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
    return File(updatesDir, fileName)
}

fun saveBytesToDownloads(
    context: Context,
    fileName: String,
    mime: String,
    bytes: ByteArray,
): Uri {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Unable to save file"
        }
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("Unable to open download location")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    } else {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Download directory unavailable")
        val outFile = File(downloadsDir, fileName)
        outFile.writeBytes(bytes)
        Uri.fromFile(outFile)
    }
}
