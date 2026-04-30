package com.example.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyAgreement
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
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
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val directUserCode: String,
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

data class DeviceIdentityInfo(
    val deviceId: String,
    val deviceName: String,
    val keyAlg: String,
    val publicKey: String,
    val safetyCode: String,
)

data class DevicePublicIdentity(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val keyAlg: String,
    val publicKey: String,
    val updatedAt: Long,
    val lastSeenAt: Long = 0L,
) {
    val safetyCode: String
        get() = DeviceIdentityManager.safetyCode(userCode, deviceId, publicKey)
}

data class LocalDirectPreKey(
    val deviceId: String,
    val keyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val signedPrekeyPublic: String,
    val signedPrekeySignature: String,
    val oneTimePreKeys: List<LocalDirectOneTimePreKey> = emptyList(),
)

data class LocalDirectOneTimePreKey(
    val id: String,
    val publicKey: String,
    val signature: String,
)

data class DirectPreKeyBundle(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val identityKeyAlg: String,
    val identityPublicKey: String,
    val identityFingerprint: String,
    val prekeyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val signedPrekeyPublic: String,
    val signedPrekeySignature: String,
    val oneTimePrekeyId: String,
    val oneTimePrekeyPublic: String,
    val oneTimePrekeySignature: String,
    val updatedAt: Long,
)

data class GroupSenderKeyBundle(
    val conversationId: Long,
    val senderUserCode: String,
    val senderUsername: String,
    val deviceId: String,
    val senderDeviceName: String,
    val senderIdentityPublicKey: String,
    val senderIdentityFingerprint: String,
    val recipientDeviceId: String,
    val epoch: Long,
    val keyId: String,
    val wrappedKey: String,
    val updatedAt: Long,
)

data class GroupKeyDevice(
    val userCode: String,
    val username: String,
    val deviceId: String,
    val deviceName: String,
    val identityKeyAlg: String,
    val identityPublicKey: String,
    val identityFingerprint: String,
    val prekeyAlg: String,
    val identityEcdhPublic: String,
    val identityEcdhSignature: String,
    val updatedAt: Long,
)

data class GroupSenderKeyState(
    val epoch: Long,
    val items: List<GroupSenderKeyBundle>,
    val devices: List<GroupKeyDevice>,
)

data class GroupSenderKeyEnvelope(
    val recipientUserCode: String,
    val recipientDeviceId: String,
    val wrappedKey: String,
)

data class LocalGroupSenderKey(
    val conversationId: Long,
    val deviceId: String,
    val epoch: Long,
    val keyId: String,
    val envelopes: List<GroupSenderKeyEnvelope>,
)

data class UserLookupResult(
    val user: ChatUser,
    val conversationId: Long,
    val isContact: Boolean,
    val outgoingPending: Boolean,
    val incomingRequestId: Long,
    val incomingPending: Boolean,
)

data class ContactRequestInfo(
    val id: Long,
    val direction: String,
    val status: String,
    val user: ChatUser,
    val createdAt: Long,
)

data class GroupLookupResult(
    val conversationId: Long,
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val isMember: Boolean,
    val pending: Boolean,
    val pendingRequestId: Long,
)

data class GroupJoinRequestInfo(
    val id: Long,
    val direction: String,
    val status: String,
    val groupCode: String,
    val title: String,
    val avatarUrl: String,
    val requester: ChatUser,
    val createdAt: Long,
)

data class GroupMemberInfo(
    val user: ChatUser,
    val role: String,
    val joinedAt: Long,
)

data class GroupAdminRequestInfo(
    val id: Long,
    val conversationId: Long,
    val status: String,
    val requesterUserCode: String,
    val requesterUsername: String,
    val targetUserCode: String,
    val targetUsername: String,
    val createdAt: Long,
)

data class ConversationManageInfo(
    val id: Long,
    val kind: String,
    val title: String,
    val groupCode: String,
    val avatarUrl: String,
    val messageTtlMs: Long,
    val ownRole: String,
    val canManage: Boolean,
    val canManageOwner: Boolean,
    val adminCount: Int,
    val adminLimit: Int,
    val keyEpoch: Long,
    val keyDeviceCount: Int,
    val keyReadyDeviceCount: Int,
    val members: List<GroupMemberInfo>,
    val pendingJoinRequests: List<GroupJoinRequestInfo>,
    val pendingAdminRequests: List<GroupAdminRequestInfo>,
)

data class HistoryPage(val items: List<ChatMessage>, val hasMore: Boolean)

data class TransferProgress(
    val label: String,
    val progress: Float,
    val bytesDone: Long,
    val totalBytes: Long,
)

val DEFAULT_SERVER_URL: String = BuildConfig.DEFAULT_SERVER_URL
const val APP_CLIENT_HEADER_NAME = "X-E2EEChat-Client"
const val APP_CLIENT_HEADER_VALUE = "android-app"

class ChatPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("e2ee_chat_prefs", Context.MODE_PRIVATE)
    private val pendingIncomingCallKey = "pending_incoming_call"

    private fun audioHeardKey(userIdentity: String, messageId: Long): String =
        "audio_heard_${userIdentity}_${messageId}"

    private fun draftKey(conversationId: Long): String {
        val owner = userCode.ifBlank { username }.ifBlank { "anonymous" }
        return "draft_text_${owner}_$conversationId"
    }

    var deviceId: String
        get() {
            val existing = prefs.getString("device_id", "") ?: ""
            if (existing.isNotBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString("device_id", value).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString("server_url", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var userCode: String
        get() = prefs.getString("user_code", "") ?: ""
        set(value) = prefs.edit().putString("user_code", value).apply()

    var savedLoginUsername: String
        get() = prefs.getString("saved_login_username", "") ?: ""
        set(value) = prefs.edit().putString("saved_login_username", value).apply()

    var savedLoginPassword: String
        get() = prefs.getString("saved_login_password", "") ?: ""
        set(value) = prefs.edit().putString("saved_login_password", value).apply()

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

    fun draftText(conversationId: Long): String {
        if (conversationId <= 0L) return ""
        return prefs.getString(draftKey(conversationId), "") ?: ""
    }

    fun putDraftText(conversationId: Long, value: String) {
        if (conversationId <= 0L) return
        val editor = prefs.edit()
        if (value.isBlank()) {
            editor.remove(draftKey(conversationId))
        } else {
            editor.putString(draftKey(conversationId), value)
        }
        editor.apply()
    }

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
        prefs.edit().remove("token").remove("username").remove("user_code").apply()
    }

    fun clearSavedLogin() {
        prefs.edit()
            .remove("saved_login_username")
            .remove("saved_login_password")
            .apply()
    }

    fun cryptoValue(key: String): String =
        prefs.getString("crypto_$key", "") ?: ""

    fun putCryptoValue(key: String, value: String) {
        prefs.edit().putString("crypto_$key", value).apply()
    }

    fun removeCryptoValue(key: String) {
        prefs.edit().remove("crypto_$key").apply()
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

object DeviceIdentityManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALG = "EC-P256-SHA256"
    private const val ALIAS_PREFIX = "e2eechat_identity_"

    fun localIdentity(prefs: ChatPreferences, userCode: String): DeviceIdentityInfo {
        val deviceId = prefs.deviceId
        val publicKey = publicKeyBase64(deviceId)
        return DeviceIdentityInfo(
            deviceId = deviceId,
            deviceName = deviceName(),
            keyAlg = KEY_ALG,
            publicKey = publicKey,
            safetyCode = safetyCode(userCode, deviceId, publicKey)
        )
    }

    private fun aliasFor(deviceId: String): String = "$ALIAS_PREFIX$deviceId"

    private fun publicKeyBase64(deviceId: String): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val alias = aliasFor(deviceId)
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val certificate = keyStore.getCertificate(alias)
            ?: throw IllegalStateException("device_identity_unavailable")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun signWithLocalIdentity(prefs: ChatPreferences, data: ByteArray): String {
        publicKeyBase64(prefs.deviceId)
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(aliasFor(prefs.deviceId), null) as? PrivateKey
            ?: throw IllegalStateException("device_identity_unavailable")
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    fun verifySignature(publicKeyBase64: String, data: ByteArray, signatureBase64: String): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.NO_WRAP)))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(Base64.decode(signatureBase64, Base64.NO_WRAP))
        }.getOrDefault(false)

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return "$maker $model".trim().replace(Regex("\\s+"), " ").ifBlank { "Android device" }
    }

    fun safetyCode(userCode: String, deviceId: String, publicKey: String): String {
        val input = "e2eechat-device-v1|$userCode|$deviceId|$publicKey"
            .toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest
            .take(16)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .chunked(4)
            .joinToString(" ")
    }
}

object ChatCrypto {
    data class EncryptedFileResult(
        val salt: String,
        val iv: String,
        val version: Int = 3,
    )

    private const val GCM_TAG_BITS = 128
    private const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun randomFileKey(): String =
        b64(ByteArray(KEY_BYTES).also(random::nextBytes))

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    fun textAad(conversationId: Long): String =
        "e2eechat:v2:text:conversation=$conversationId:kind=text"

    fun attachmentMetaAad(conversationId: Long, kind: String): String =
        "e2eechat:v2:attachment-meta:conversation=$conversationId:kind=$kind"

    fun attachmentFileAad(conversationId: Long, kind: String): String =
        "e2eechat:v2:attachment-file:conversation=$conversationId:kind=$kind"

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(KEY_BYTES), "HmacSHA256"))
        val pseudorandomKey = mac.doFinal(inputKeyMaterial)
        var previous = ByteArray(0)
        val output = ByteArrayOutputStream()
        var counter = 1
        while (output.size() < length) {
            mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    private fun initAeadCipher(mode: Int, key: SecretKeySpec, iv: ByteArray, aad: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        }

    private fun rawAeadKey(fileKey: String, purpose: String, aad: String): SecretKeySpec {
        val keyBytes = hkdfSha256(fromB64(fileKey), "e2eechat:v3:$purpose:$aad".toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptTextWithKey(fileKey: String, plaintext: String, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = initAeadCipher(Cipher.ENCRYPT_MODE, rawAeadKey(fileKey, "text", aad), iv, aad)
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = JSONObject()
            .put("v", 3)
            .put("kdf", "HKDF-SHA256")
            .put("aead", "AES-256-GCM")
            .put("iv", b64(iv))
            .put("ct", b64(encrypted))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptTextWithKey(fileKey: String, payload: String, aad: String): String {
        val json = JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))
        val cipher = initAeadCipher(Cipher.DECRYPT_MODE, rawAeadKey(fileKey, "text", aad), fromB64(json.getString("iv")), aad)
        return String(cipher.doFinal(fromB64(json.getString("ct"))), StandardCharsets.UTF_8)
    }

    fun encryptBinaryFileWithKey(fileKey: String, sourceFile: File, targetFile: File, aad: String): EncryptedFileResult {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = initAeadCipher(Cipher.ENCRYPT_MODE, rawAeadKey(fileKey, "attachment-file", aad), iv, aad)
        targetFile.parentFile?.mkdirs()
        try {
            sourceFile.inputStream().use { input ->
                CipherOutputStream(targetFile.outputStream(), cipher).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
        return EncryptedFileResult(salt = "", iv = b64(iv), version = 3)
    }

    fun decryptBinaryFileWithKey(
        fileKey: String,
        iv: String,
        sourceFile: File,
        targetFile: File,
        aad: String,
    ) {
        targetFile.parentFile?.mkdirs()
        try {
            val cipher = initAeadCipher(
                Cipher.DECRYPT_MODE,
                rawAeadKey(fileKey, "attachment-file", aad),
                fromB64(iv),
                aad
            )
            sourceFile.inputStream().use { input ->
                CipherInputStream(input, cipher).use { decrypted ->
                    targetFile.outputStream().use { output ->
                        decrypted.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
    }
}

object DirectMessageCrypto {
    private const val SCHEME_V1 = "e2eechat-direct-dr-v1"
    private const val SCHEME_V2 = "e2eechat-direct-dr-v2"
    private const val KEY_ALG = "EC-P256-X3DH-DR-v2"
    private const val LEGACY_KEY_ALG = "EC-P256-X3DH-DR-v1"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val MAX_RATCHET_STEPS = 1000
    private const val ONE_TIME_PREKEY_MIN = 20
    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha256Hex(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        return hkdfSha256(inputKeyMaterial, ByteArray(KEY_BYTES), info, length)
    }

    private fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.copyOf(KEY_BYTES), "HmacSHA256"))
        val pseudorandomKey = mac.doFinal(inputKeyMaterial)
        var previous = ByteArray(0)
        val output = ByteArrayOutputStream()
        var counter = 1
        val infoBytes = info.toByteArray(StandardCharsets.UTF_8)
        while (output.size() < length) {
            mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    private fun generateEcKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }

    private fun publicKeyFromB64(value: String) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(fromB64(value)))

    private fun privateKeyFromB64(value: String) =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(fromB64(value)))

    private fun ecdh(privateKey: PrivateKey, publicKey: java.security.PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    private fun initCipher(mode: Int, keyBytes: ByteArray, iv: ByteArray, aad: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keyBytes.copyOf(KEY_BYTES), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        }

    private fun payloadJson(payload: String): JSONObject =
        JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))

    fun isDirectPayload(payload: String): Boolean =
        runCatching {
            val scheme = payloadJson(payload).optString("scheme")
            scheme == SCHEME_V1 || scheme == SCHEME_V2
        }.getOrDefault(false)

    private data class LocalEcdhIdentity(
        val publicKey: String,
        val privateKey: String,
        val signature: String,
    )

    private fun randomId(): String {
        val bytes = ByteArray(12).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun generateStoredKeyPair(prefs: ChatPreferences, publicKeyKey: String, privateKeyKey: String): Pair<String, String> {
        var publicKey = prefs.cryptoValue(publicKeyKey)
        var privateKey = prefs.cryptoValue(privateKeyKey)
        if (publicKey.isBlank() || privateKey.isBlank()) {
            val pair = generateEcKeyPair()
            publicKey = b64(pair.public.encoded)
            privateKey = b64(pair.private.encoded)
            prefs.putCryptoValue(publicKeyKey, publicKey)
            prefs.putCryptoValue(privateKeyKey, privateKey)
        }
        return publicKey to privateKey
    }

    private fun localEcdhIdentity(prefs: ChatPreferences): LocalEcdhIdentity {
        val deviceId = prefs.deviceId
        val (publicKey, privateKey) = generateStoredKeyPair(
            prefs,
            "direct_identity_ecdh_public_$deviceId",
            "direct_identity_ecdh_private_$deviceId"
        )
        val signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey))
        prefs.putCryptoValue("direct_identity_ecdh_signature_$deviceId", signature)
        return LocalEcdhIdentity(publicKey, privateKey, signature)
    }

    private fun parseIdList(raw: String): MutableList<String> =
        runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.optString(index) }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()
        }.getOrDefault(mutableListOf())

    private fun persistIdList(prefs: ChatPreferences, key: String, ids: List<String>) {
        val array = JSONArray()
        ids.distinct().forEach { array.put(it) }
        prefs.putCryptoValue(key, array.toString())
    }

    private fun ensureOneTimePreKeys(prefs: ChatPreferences): List<LocalDirectOneTimePreKey> {
        val deviceId = prefs.deviceId
        val idsKey = "direct_otk_ids_$deviceId"
        val ids = parseIdList(prefs.cryptoValue(idsKey))
            .filter { prefs.cryptoValue("direct_otk_private_${deviceId}_$it").isNotBlank() }
            .toMutableList()
        while (ids.size < ONE_TIME_PREKEY_MIN) {
            val id = randomId()
            val pair = generateEcKeyPair()
            prefs.putCryptoValue("direct_otk_public_${deviceId}_$id", b64(pair.public.encoded))
            prefs.putCryptoValue("direct_otk_private_${deviceId}_$id", b64(pair.private.encoded))
            ids.add(id)
        }
        persistIdList(prefs, idsKey, ids)
        return ids.mapNotNull { id ->
            val publicKey = prefs.cryptoValue("direct_otk_public_${deviceId}_$id")
            if (publicKey.isBlank()) return@mapNotNull null
            LocalDirectOneTimePreKey(
                id = id,
                publicKey = publicKey,
                signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey))
            )
        }
    }

    fun ensureLocalPreKey(prefs: ChatPreferences): LocalDirectPreKey {
        val deviceId = prefs.deviceId
        val publicKeyKey = "direct_prekey_public_$deviceId"
        val privateKeyKey = "direct_prekey_private_$deviceId"
        val signatureKey = "direct_prekey_signature_$deviceId"
        val identity = localEcdhIdentity(prefs)
        val (publicKey, _) = generateStoredKeyPair(prefs, publicKeyKey, privateKeyKey)
        val signature = DeviceIdentityManager.signWithLocalIdentity(prefs, fromB64(publicKey)).also {
            prefs.putCryptoValue(signatureKey, it)
        }
        return LocalDirectPreKey(
            deviceId = deviceId,
            keyAlg = KEY_ALG,
            identityEcdhPublic = identity.publicKey,
            identityEcdhSignature = identity.signature,
            signedPrekeyPublic = publicKey,
            signedPrekeySignature = signature,
            oneTimePreKeys = ensureOneTimePreKeys(prefs)
        )
    }

    private fun localPreKeyPrivate(prefs: ChatPreferences): PrivateKey {
        ensureLocalPreKey(prefs)
        return privateKeyFromB64(prefs.cryptoValue("direct_prekey_private_${prefs.deviceId}"))
    }

    private fun localIdentityEcdhPrivate(prefs: ChatPreferences): PrivateKey {
        localEcdhIdentity(prefs)
        return privateKeyFromB64(prefs.cryptoValue("direct_identity_ecdh_private_${prefs.deviceId}"))
    }

    fun localIdentityEcdhPublic(prefs: ChatPreferences): String =
        localEcdhIdentity(prefs).publicKey

    fun ecdhWithLocalIdentity(prefs: ChatPreferences, peerPublicKey: String): ByteArray =
        ecdh(localIdentityEcdhPrivate(prefs), publicKeyFromB64(peerPublicKey))

    fun ephemeralEcdh(peerPublicKey: String): Pair<String, ByteArray> {
        val pair = generateEcKeyPair()
        return b64(pair.public.encoded) to ecdh(pair.private, publicKeyFromB64(peerPublicKey))
    }

    private fun localOneTimePreKeyPrivate(prefs: ChatPreferences, id: String): PrivateKey? {
        if (id.isBlank()) return null
        val raw = prefs.cryptoValue("direct_otk_private_${prefs.deviceId}_$id")
        return raw.takeIf { it.isNotBlank() }?.let(::privateKeyFromB64)
    }

    private fun supportsV2(bundle: DirectPreKeyBundle): Boolean =
        bundle.prekeyAlg == KEY_ALG &&
            bundle.identityEcdhPublic.isNotBlank() &&
            bundle.identityEcdhSignature.isNotBlank()

    private fun verifyBundleV1(bundle: DirectPreKeyBundle) {
        val isValid = (bundle.prekeyAlg == LEGACY_KEY_ALG || bundle.prekeyAlg == KEY_ALG) &&
            bundle.signedPrekeyPublic.isNotBlank() &&
            bundle.identityPublicKey.isNotBlank() &&
            DeviceIdentityManager.verifySignature(
                bundle.identityPublicKey,
                fromB64(bundle.signedPrekeyPublic),
                bundle.signedPrekeySignature
            )
        require(isValid) { "bad_direct_prekey_signature" }
    }

    private fun verifyBundleV2(bundle: DirectPreKeyBundle) {
        val signedPrekeyValid = DeviceIdentityManager.verifySignature(
            bundle.identityPublicKey,
            fromB64(bundle.signedPrekeyPublic),
            bundle.signedPrekeySignature
        )
        val identityEcdhValid = DeviceIdentityManager.verifySignature(
            bundle.identityPublicKey,
            fromB64(bundle.identityEcdhPublic),
            bundle.identityEcdhSignature
        )
        val oneTimePrekeyValid = bundle.oneTimePrekeyPublic.isBlank() ||
            DeviceIdentityManager.verifySignature(
                bundle.identityPublicKey,
                fromB64(bundle.oneTimePrekeyPublic),
                bundle.oneTimePrekeySignature
            )
        require(
            supportsV2(bundle) &&
                bundle.signedPrekeyPublic.isNotBlank() &&
                signedPrekeyValid &&
                identityEcdhValid &&
                oneTimePrekeyValid
        ) { "bad_direct_prekey_signature" }
    }

    private fun rootInfoV1(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        ephemeralPublic: String,
        recipientPrekeyPublic: String,
    ): String =
        "e2eechat:direct:v1:root|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|eph=$ephemeralPublic|spk=$recipientPrekeyPublic"

    private fun chainInfoV1(rootInfo: String): String = "$rootInfo|chain"

    private fun aadV1(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        ephemeralPublic: String,
        counter: Long,
    ): String =
        "e2eechat:direct:v1:aad|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|eph=$ephemeralPublic|n=$counter"

    private fun sendStateKey(conversationId: Long, recipientDeviceId: String): String =
        "direct_send_${conversationId}_$recipientDeviceId"

    private fun receiveStateKey(conversationId: Long, senderDeviceId: String, ephemeralPublic: String): String =
        "direct_recv_${conversationId}_${senderDeviceId}_${sha256Hex(ephemeralPublic)}"

    private fun messageKeyName(prefix: String, conversationId: Long, peerDeviceId: String, ephemeralPublic: String, counter: Long): String =
        "${prefix}_${conversationId}_${peerDeviceId}_${sha256Hex(ephemeralPublic)}_$counter"

    private fun decodeState(raw: String): JSONObject? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun bundleKey(conversationId: Long): String = "direct_peer_bundle_v2_$conversationId"

    fun rememberRecipientBundle(prefs: ChatPreferences, conversationId: Long, bundle: DirectPreKeyBundle) {
        prefs.putCryptoValue(
            bundleKey(conversationId),
            JSONObject()
                .put("user_code", bundle.userCode)
                .put("username", bundle.username)
                .put("device_id", bundle.deviceId)
                .put("device_name", bundle.deviceName)
                .put("identity_key_alg", bundle.identityKeyAlg)
                .put("identity_public_key", bundle.identityPublicKey)
                .put("identity_fingerprint", bundle.identityFingerprint)
                .put("prekey_alg", bundle.prekeyAlg)
                .put("identity_ecdh_public", bundle.identityEcdhPublic)
                .put("identity_ecdh_signature", bundle.identityEcdhSignature)
                .put("signed_prekey_public", bundle.signedPrekeyPublic)
                .put("signed_prekey_signature", bundle.signedPrekeySignature)
                .put("one_time_prekey_id", bundle.oneTimePrekeyId)
                .put("one_time_prekey_public", bundle.oneTimePrekeyPublic)
                .put("one_time_prekey_signature", bundle.oneTimePrekeySignature)
                .put("updated_at", bundle.updatedAt)
                .toString()
        )
    }

    fun cachedRecipientBundle(prefs: ChatPreferences, conversationId: Long): DirectPreKeyBundle? =
        decodeState(prefs.cryptoValue(bundleKey(conversationId)))?.let(::bundleFromJson)

    private fun bundleFromJson(item: JSONObject): DirectPreKeyBundle =
        DirectPreKeyBundle(
            userCode = item.optString("user_code"),
            username = item.optString("username"),
            deviceId = item.optString("device_id"),
            deviceName = item.optString("device_name"),
            identityKeyAlg = item.optString("identity_key_alg"),
            identityPublicKey = item.optString("identity_public_key"),
            identityFingerprint = item.optString("identity_fingerprint"),
            prekeyAlg = item.optString("prekey_alg"),
            identityEcdhPublic = item.optString("identity_ecdh_public"),
            identityEcdhSignature = item.optString("identity_ecdh_signature"),
            signedPrekeyPublic = item.optString("signed_prekey_public"),
            signedPrekeySignature = item.optString("signed_prekey_signature"),
            oneTimePrekeyId = item.optString("one_time_prekey_id"),
            oneTimePrekeyPublic = item.optString("one_time_prekey_public"),
            oneTimePrekeySignature = item.optString("one_time_prekey_signature"),
            updatedAt = item.optLong("updated_at", 0L)
        )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun kdfRoot(rootKey: ByteArray, dhSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val output = hkdfSha256(dhSecret, rootKey, "e2eechat:direct:v2:kdf-root", KEY_BYTES * 2)
        return output.copyOfRange(0, KEY_BYTES) to output.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
    }

    private fun x3dhInfo(
        conversationId: Long,
        senderDeviceId: String,
        recipientDeviceId: String,
        senderIdentityEcdhPublic: String,
        recipientIdentityEcdhPublic: String,
        recipientSignedPrekeyPublic: String,
        recipientOneTimePrekeyPublic: String,
    ): String =
        "e2eechat:direct:v2:x3dh|conversation=$conversationId|from=$senderDeviceId|to=$recipientDeviceId|ika=$senderIdentityEcdhPublic|ikb=$recipientIdentityEcdhPublic|spkb=$recipientSignedPrekeyPublic|opkb=$recipientOneTimePrekeyPublic"

    private fun sessionStateKey(conversationId: Long, peerDeviceId: String): String =
        "direct_session_v2_${conversationId}_$peerDeviceId"

    private fun messageKeyNameV2(prefix: String, conversationId: Long, peerDeviceId: String, sessionId: String, dhPublic: String, counter: Long): String =
        "${prefix}_${conversationId}_${peerDeviceId}_${sessionId}_${sha256Hex(dhPublic)}_$counter"

    private fun aadV2(
        conversationId: Long,
        sessionId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        dhPublic: String,
        previousSendCount: Long,
        counter: Long,
    ): String =
        "e2eechat:direct:v2:aad|conversation=$conversationId|session=$sessionId|from=$senderDeviceId|to=$recipientDeviceId|dh=$dhPublic|pn=$previousSendCount|n=$counter"

    private fun deriveMessageKey(chainKey: ByteArray): ByteArray =
        hkdfSha256(chainKey, "e2eechat:direct:v2:message-key")

    private fun nextChainKey(chainKey: ByteArray): ByteArray =
        hkdfSha256(chainKey, "e2eechat:direct:v2:next-chain")

    private fun createInitiatorSession(prefs: ChatPreferences, conversationId: Long, recipient: DirectPreKeyBundle): JSONObject {
        verifyBundleV2(recipient)
        val senderDeviceId = prefs.deviceId
        val senderIdentity = localEcdhIdentity(prefs)
        val senderIdentityPrivate = privateKeyFromB64(senderIdentity.privateKey)
        val x3dhEphemeral = generateEcKeyPair()
        val ratchet = generateEcKeyPair()
        val recipientIdentityPublic = publicKeyFromB64(recipient.identityEcdhPublic)
        val recipientSignedPrekeyPublic = publicKeyFromB64(recipient.signedPrekeyPublic)
        val recipientOneTimePrekeyPublic = recipient.oneTimePrekeyPublic.takeIf { it.isNotBlank() }?.let(::publicKeyFromB64)
        val material = concat(
            ecdh(senderIdentityPrivate, recipientSignedPrekeyPublic),
            ecdh(x3dhEphemeral.private, recipientIdentityPublic),
            ecdh(x3dhEphemeral.private, recipientSignedPrekeyPublic),
            recipientOneTimePrekeyPublic?.let { ecdh(x3dhEphemeral.private, it) } ?: ByteArray(0)
        )
        val x3dhEphemeralPublic = b64(x3dhEphemeral.public.encoded)
        val rootKey = hkdfSha256(
            material,
            x3dhInfo(
                conversationId,
                senderDeviceId,
                recipient.deviceId,
                senderIdentity.publicKey,
                recipient.identityEcdhPublic,
                recipient.signedPrekeyPublic,
                recipient.oneTimePrekeyPublic
            )
        )
        val (nextRoot, sendChain) = kdfRoot(rootKey, ecdh(ratchet.private, recipientSignedPrekeyPublic))
        val sessionId = sha256Hex(
            "e2eechat:direct:v2:session|$conversationId|$senderDeviceId|${recipient.deviceId}|${senderIdentity.publicKey}|${recipient.identityEcdhPublic}|$x3dhEphemeralPublic|${recipient.oneTimePrekeyId}"
        ).take(32)
        return JSONObject()
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("peer_device_id", recipient.deviceId)
            .put("root_key", b64(nextRoot))
            .put("send_chain_key", b64(sendChain))
            .put("recv_chain_key", "")
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("previous_send_count", 0L)
            .put("local_dh_public", b64(ratchet.public.encoded))
            .put("local_dh_private", b64(ratchet.private.encoded))
            .put("remote_dh_public", recipient.signedPrekeyPublic)
            .put("initial", true)
            .put("sender_identity_ecdh_public", senderIdentity.publicKey)
            .put("sender_identity_ecdh_signature", senderIdentity.signature)
            .put("x3dh_ephemeral_public", x3dhEphemeralPublic)
            .put("recipient_identity_ecdh_public", recipient.identityEcdhPublic)
            .put("recipient_signed_prekey_public", recipient.signedPrekeyPublic)
            .put("recipient_one_time_prekey_id", recipient.oneTimePrekeyId)
            .put("recipient_one_time_prekey_public", recipient.oneTimePrekeyPublic)
    }

    private fun x3dhRecipientRoot(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): ByteArray {
        val senderDeviceId = payload.getString("sender_device_id")
        val senderIdentityEcdhPublic = payload.getString("sender_identity_ecdh_public")
        val recipientIdentityEcdhPublic = payload.optString("recipient_identity_ecdh_public")
        val recipientSignedPrekeyPublic = payload.getString("recipient_signed_prekey_public")
        val recipientOneTimePrekeyId = payload.optString("recipient_one_time_prekey_id")
        val recipientOneTimePrekeyPublic = payload.optString("recipient_one_time_prekey_public")
        val x3dhEphemeralPublic = payload.getString("x3dh_ephemeral_public")
        val senderIdentityPublic = publicKeyFromB64(senderIdentityEcdhPublic)
        val x3dhEphemeral = publicKeyFromB64(x3dhEphemeralPublic)
        val signedPrekeyPrivate = localPreKeyPrivate(prefs)
        val identityPrivate = localIdentityEcdhPrivate(prefs)
        val oneTimePrivate = localOneTimePreKeyPrivate(prefs, recipientOneTimePrekeyId)
        val material = concat(
            ecdh(signedPrekeyPrivate, senderIdentityPublic),
            ecdh(identityPrivate, x3dhEphemeral),
            ecdh(signedPrekeyPrivate, x3dhEphemeral),
            oneTimePrivate?.let { ecdh(it, x3dhEphemeral) } ?: ByteArray(0)
        )
        return hkdfSha256(
            material,
            x3dhInfo(
                message.conversationId,
                senderDeviceId,
                prefs.deviceId,
                senderIdentityEcdhPublic,
                recipientIdentityEcdhPublic,
                recipientSignedPrekeyPublic,
                recipientOneTimePrekeyPublic
            )
        )
    }

    private fun createReceiverSession(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): JSONObject {
        require(payload.optBoolean("init", false)) { "direct_session_required" }
        val senderDeviceId = payload.getString("sender_device_id")
        val sessionId = payload.getString("session_id")
        val senderRatchetPublic = payload.getString("dh_pub")
        val rootKey = x3dhRecipientRoot(prefs, message, payload)
        val signedPrekeyPrivate = localPreKeyPrivate(prefs)
        val (rootAfterRecv, recvChain) = kdfRoot(rootKey, ecdh(signedPrekeyPrivate, publicKeyFromB64(senderRatchetPublic)))
        val replyRatchet = generateEcKeyPair()
        val (rootAfterSend, sendChain) = kdfRoot(rootAfterRecv, ecdh(replyRatchet.private, publicKeyFromB64(senderRatchetPublic)))
        return JSONObject()
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("peer_device_id", senderDeviceId)
            .put("root_key", b64(rootAfterSend))
            .put("send_chain_key", b64(sendChain))
            .put("recv_chain_key", b64(recvChain))
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("previous_send_count", 0L)
            .put("local_dh_public", b64(replyRatchet.public.encoded))
            .put("local_dh_private", b64(replyRatchet.private.encoded))
            .put("remote_dh_public", senderRatchetPublic)
            .put("initial", false)
    }

    private fun ratchetForNewRemote(state: JSONObject, newRemoteDhPublic: String) {
        val localPrivate = privateKeyFromB64(state.getString("local_dh_private"))
        val (rootAfterRecv, recvChain) = kdfRoot(
            fromB64(state.getString("root_key")),
            ecdh(localPrivate, publicKeyFromB64(newRemoteDhPublic))
        )
        val newLocal = generateEcKeyPair()
        val (rootAfterSend, sendChain) = kdfRoot(rootAfterRecv, ecdh(newLocal.private, publicKeyFromB64(newRemoteDhPublic)))
        state
            .put("root_key", b64(rootAfterSend))
            .put("recv_chain_key", b64(recvChain))
            .put("send_chain_key", b64(sendChain))
            .put("previous_send_count", state.optLong("send_count", 0L))
            .put("send_count", 0L)
            .put("recv_count", 0L)
            .put("local_dh_public", b64(newLocal.public.encoded))
            .put("local_dh_private", b64(newLocal.private.encoded))
            .put("remote_dh_public", newRemoteDhPublic)
            .put("initial", false)
    }

    fun encryptText(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        if (supportsV2(recipient)) {
            return encryptTextV2(prefs, conversationId, recipient, plaintext)
        }
        return encryptTextV1(prefs, conversationId, recipient, plaintext)
    }

    private fun encryptTextV2(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        val senderDeviceId = prefs.deviceId
        val stateKey = sessionStateKey(conversationId, recipient.deviceId)
        val state = decodeState(prefs.cryptoValue(stateKey))?.takeIf {
            it.optString("scheme") == SCHEME_V2 &&
                it.optString("peer_device_id") == recipient.deviceId &&
                it.optString("send_chain_key").isNotBlank()
        } ?: createInitiatorSession(prefs, conversationId, recipient)
        val sessionId = state.getString("session_id")
        val counter = state.optLong("send_count", 0L)
        val previousSendCount = state.optLong("previous_send_count", 0L)
        val dhPublic = state.getString("local_dh_public")
        val chainKey = fromB64(state.getString("send_chain_key"))
        val messageKey = deriveMessageKey(chainKey)
        val nextChain = nextChainKey(chainKey)
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aadV2(conversationId, sessionId, senderDeviceId, recipient.deviceId, dhPublic, previousSendCount, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey, iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        prefs.putCryptoValue(
            messageKeyNameV2("direct_v2_sent_key", conversationId, recipient.deviceId, sessionId, dhPublic, counter),
            b64(messageKey)
        )
        state.put("send_chain_key", b64(nextChain)).put("send_count", counter + 1L)
        prefs.putCryptoValue(stateKey, state.toString())
        rememberRecipientBundle(prefs, conversationId, recipient)

        val payload = JSONObject()
            .put("v", 2)
            .put("scheme", SCHEME_V2)
            .put("session_id", sessionId)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_device_id", recipient.deviceId)
            .put("dh_pub", dhPublic)
            .put("pn", previousSendCount)
            .put("n", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        if (state.optBoolean("initial", false)) {
            payload
                .put("init", true)
                .put("sender_identity_ecdh_public", state.getString("sender_identity_ecdh_public"))
                .put("sender_identity_ecdh_signature", state.getString("sender_identity_ecdh_signature"))
                .put("x3dh_ephemeral_public", state.getString("x3dh_ephemeral_public"))
                .put("recipient_identity_ecdh_public", state.getString("recipient_identity_ecdh_public"))
                .put("recipient_signed_prekey_public", state.getString("recipient_signed_prekey_public"))
                .put("recipient_one_time_prekey_id", state.optString("recipient_one_time_prekey_id"))
                .put("recipient_one_time_prekey_public", state.optString("recipient_one_time_prekey_public"))
        }
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun encryptTextV1(
        prefs: ChatPreferences,
        conversationId: Long,
        recipient: DirectPreKeyBundle,
        plaintext: String,
    ): String {
        verifyBundleV1(recipient)
        val senderDeviceId = prefs.deviceId
        val stateKey = sendStateKey(conversationId, recipient.deviceId)
        val existing = decodeState(prefs.cryptoValue(stateKey))
        val state = if (
            existing == null ||
            existing.optString("recipient_prekey_public") != recipient.signedPrekeyPublic ||
            existing.optString("recipient_device_id") != recipient.deviceId
        ) {
            val ephemeral = generateEcKeyPair()
            val ephemeralPublic = b64(ephemeral.public.encoded)
            val shared = ecdh(ephemeral.private, publicKeyFromB64(recipient.signedPrekeyPublic))
            val rootInfo = rootInfoV1(
                conversationId,
                senderDeviceId,
                recipient.deviceId,
                ephemeralPublic,
                recipient.signedPrekeyPublic
            )
            JSONObject()
                .put("recipient_device_id", recipient.deviceId)
                .put("recipient_prekey_public", recipient.signedPrekeyPublic)
                .put("ephemeral_public", ephemeralPublic)
                .put("chain_key", b64(hkdfSha256(shared, chainInfoV1(rootInfo))))
                .put("counter", 0L)
        } else {
            existing
        }

        val counter = state.optLong("counter", 0L)
        val chainKey = fromB64(state.getString("chain_key"))
        val messageKey = hkdfSha256(chainKey, "e2eechat:direct:v1:message-key")
        val nextChainKey = hkdfSha256(chainKey, "e2eechat:direct:v1:next-chain")
        val ephemeralPublic = state.getString("ephemeral_public")
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aadV1(conversationId, senderDeviceId, recipient.deviceId, ephemeralPublic, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey, iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        prefs.putCryptoValue(
            messageKeyName("direct_sent_key", conversationId, recipient.deviceId, ephemeralPublic, counter),
            b64(messageKey)
        )
        state.put("chain_key", b64(nextChainKey)).put("counter", counter + 1L)
        prefs.putCryptoValue(stateKey, state.toString())

        val payload = JSONObject()
            .put("v", 1)
            .put("scheme", SCHEME_V1)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_device_id", recipient.deviceId)
            .put("ephemeral_public", ephemeralPublic)
            .put("recipient_prekey_public", state.getString("recipient_prekey_public"))
            .put("counter", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptText(prefs: ChatPreferences, message: ChatMessage): String {
        val payload = payloadJson(message.payload)
        return when (payload.optString("scheme")) {
            SCHEME_V2 -> decryptTextV2(prefs, message, payload)
            SCHEME_V1 -> decryptTextV1(prefs, message, payload)
            else -> throw IllegalStateException("not_direct_ratchet_payload")
        }
    }

    fun decryptText(prefs: ChatPreferences, conversationId: Long, payloadText: String): String =
        decryptText(
            prefs,
            ChatMessage(
                id = 0L,
                conversationId = conversationId,
                ts = 0L,
                expiresAt = 0L,
                username = "",
                color = "",
                kind = "text",
                payload = payloadText,
                e2ee = true,
                replyTo = null,
                mentions = emptyList()
            )
        )

    private fun decryptTextV2(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): String {
        val senderDeviceId = payload.getString("sender_device_id")
        val recipientDeviceId = payload.getString("recipient_device_id")
        val sessionId = payload.getString("session_id")
        val dhPublic = payload.getString("dh_pub")
        val counter = payload.optLong("n", 0L)
        val previousSendCount = payload.optLong("pn", 0L)
        val aad = aadV2(message.conversationId, sessionId, senderDeviceId, recipientDeviceId, dhPublic, previousSendCount, counter)
        val messageKey = if (senderDeviceId == prefs.deviceId) {
            prefs.cryptoValue(messageKeyNameV2("direct_v2_sent_key", message.conversationId, recipientDeviceId, sessionId, dhPublic, counter))
                .takeIf { it.isNotBlank() }
                ?.let(::fromB64)
                ?: throw IllegalStateException("direct_message_key_unavailable")
        } else {
            require(recipientDeviceId == prefs.deviceId) { "message_not_for_this_device" }
            val cachedKey = prefs.cryptoValue(messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, dhPublic, counter))
            if (cachedKey.isNotBlank()) {
                fromB64(cachedKey)
            } else {
                val stateKey = sessionStateKey(message.conversationId, senderDeviceId)
                val state = decodeState(prefs.cryptoValue(stateKey)) ?: createReceiverSession(prefs, message, payload)
                if (state.optString("remote_dh_public") != dhPublic) {
                    val oldRecvChain = state.optString("recv_chain_key")
                    val oldRecvCount = state.optLong("recv_count", 0L)
                    val oldRemoteDh = state.optString("remote_dh_public")
                    var chain = oldRecvChain.takeIf { it.isNotBlank() }?.let(::fromB64)
                    var skippedCounter = oldRecvCount
                    while (chain != null && oldRemoteDh.isNotBlank() && skippedCounter < previousSendCount && skippedCounter - oldRecvCount <= MAX_RATCHET_STEPS) {
                        val skippedKey = deriveMessageKey(chain)
                        prefs.putCryptoValue(
                            messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, oldRemoteDh, skippedCounter),
                            b64(skippedKey)
                        )
                        chain = nextChainKey(chain)
                        skippedCounter += 1L
                    }
                    ratchetForNewRemote(state, dhPublic)
                }
                var currentCounter = state.optLong("recv_count", 0L)
                require(counter >= currentCounter && counter - currentCounter <= MAX_RATCHET_STEPS) {
                    "direct_ratchet_counter_out_of_range"
                }
                var chainKey = fromB64(state.getString("recv_chain_key"))
                var selectedKey: ByteArray? = null
                while (currentCounter <= counter) {
                    val key = deriveMessageKey(chainKey)
                    prefs.putCryptoValue(
                        messageKeyNameV2("direct_v2_recv_key", message.conversationId, senderDeviceId, sessionId, dhPublic, currentCounter),
                        b64(key)
                    )
                    if (currentCounter == counter) selectedKey = key
                    chainKey = nextChainKey(chainKey)
                    currentCounter += 1L
                }
                state.put("recv_chain_key", b64(chainKey)).put("recv_count", currentCounter)
                prefs.putCryptoValue(stateKey, state.toString())
                selectedKey ?: throw IllegalStateException("direct_message_key_unavailable")
            }
        }

        val plain = initCipher(
            Cipher.DECRYPT_MODE,
            messageKey,
            fromB64(payload.getString("iv")),
            aad
        ).doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun decryptTextV1(prefs: ChatPreferences, message: ChatMessage, payload: JSONObject): String {
        val senderDeviceId = payload.getString("sender_device_id")
        val recipientDeviceId = payload.getString("recipient_device_id")
        val ephemeralPublic = payload.getString("ephemeral_public")
        val counter = payload.optLong("counter", 0L)
        val aad = aadV1(message.conversationId, senderDeviceId, recipientDeviceId, ephemeralPublic, counter)
        val messageKey = if (senderDeviceId == prefs.deviceId) {
            prefs.cryptoValue(messageKeyName("direct_sent_key", message.conversationId, recipientDeviceId, ephemeralPublic, counter))
                .takeIf { it.isNotBlank() }
                ?.let(::fromB64)
                ?: throw IllegalStateException("direct_message_key_unavailable")
        } else {
            require(recipientDeviceId == prefs.deviceId) { "message_not_for_this_device" }
            val cachedKey = prefs.cryptoValue(messageKeyName("direct_recv_key", message.conversationId, senderDeviceId, ephemeralPublic, counter))
            if (cachedKey.isNotBlank()) {
                fromB64(cachedKey)
            } else {
                val stateKey = receiveStateKey(message.conversationId, senderDeviceId, ephemeralPublic)
                val state = decodeState(prefs.cryptoValue(stateKey)) ?: run {
                    val recipientPrekeyPublic = payload.getString("recipient_prekey_public")
                    val shared = ecdh(localPreKeyPrivate(prefs), publicKeyFromB64(ephemeralPublic))
                    val rootInfo = rootInfoV1(
                        message.conversationId,
                        senderDeviceId,
                        recipientDeviceId,
                        ephemeralPublic,
                        recipientPrekeyPublic
                    )
                    JSONObject()
                        .put("sender_device_id", senderDeviceId)
                        .put("ephemeral_public", ephemeralPublic)
                        .put("chain_key", b64(hkdfSha256(shared, chainInfoV1(rootInfo))))
                        .put("counter", 0L)
                }
                var currentCounter = state.optLong("counter", 0L)
                require(counter >= currentCounter && counter - currentCounter <= MAX_RATCHET_STEPS) {
                    "direct_ratchet_counter_out_of_range"
                }
                var chainKey = fromB64(state.getString("chain_key"))
                var selectedKey: ByteArray? = null
                while (currentCounter <= counter) {
                    val key = hkdfSha256(chainKey, "e2eechat:direct:v1:message-key")
                    prefs.putCryptoValue(
                        messageKeyName("direct_recv_key", message.conversationId, senderDeviceId, ephemeralPublic, currentCounter),
                        b64(key)
                    )
                    if (currentCounter == counter) selectedKey = key
                    chainKey = hkdfSha256(chainKey, "e2eechat:direct:v1:next-chain")
                    currentCounter += 1L
                }
                state.put("chain_key", b64(chainKey)).put("counter", currentCounter)
                prefs.putCryptoValue(stateKey, state.toString())
                selectedKey ?: throw IllegalStateException("direct_message_key_unavailable")
            }
        }

        val plain = initCipher(
            Cipher.DECRYPT_MODE,
            messageKey,
            fromB64(payload.getString("iv")),
            aad
        ).doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
    }
}

object GroupSenderKeyCrypto {
    private const val SCHEME = "e2eechat-group-sender-v1"
    private const val ENVELOPE_SCHEME = "e2eechat-group-sender-envelope-v1"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun fromB64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun hkdfSha256(inputKeyMaterial: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(KEY_BYTES), "HmacSHA256"))
        val pseudorandomKey = mac.doFinal(inputKeyMaterial)
        var previous = ByteArray(0)
        val output = ByteArrayOutputStream()
        var counter = 1
        val infoBytes = info.toByteArray(StandardCharsets.UTF_8)
        while (output.size() < length) {
            mac.init(SecretKeySpec(pseudorandomKey, "HmacSHA256"))
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            output.write(previous)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    private fun initCipher(mode: Int, keyBytes: ByteArray, iv: ByteArray, aad: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keyBytes.copyOf(KEY_BYTES), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        }

    private fun keyId(): String =
        ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun senderKeyName(conversationId: Long, senderUserCode: String, deviceId: String, epoch: Long, keyId: String): String =
        "group_sender_key_${conversationId}_${senderUserCode}_${deviceId}_${epoch}_$keyId"

    private fun ownKeyRefName(conversationId: Long, epoch: Long): String =
        "group_sender_own_ref_${conversationId}_$epoch"

    private fun sendCounterName(conversationId: Long, epoch: Long, keyId: String): String =
        "group_sender_counter_${conversationId}_${epoch}_$keyId"

    private fun aad(
        conversationId: Long,
        senderUserCode: String,
        deviceId: String,
        epoch: Long,
        keyId: String,
        counter: Long,
    ): String =
        "e2eechat:group-sender:v1|conversation=$conversationId|sender=$senderUserCode|device=$deviceId|epoch=$epoch|key=$keyId|n=$counter"

    private fun messageKey(senderKey: String, counter: Long): ByteArray =
        hkdfSha256(fromB64(senderKey), "e2eechat:group-sender:v1:message-key:$counter")

    private fun envelopeAad(
        conversationId: Long,
        senderUserCode: String,
        senderDeviceId: String,
        recipientUserCode: String,
        recipientDeviceId: String,
        epoch: Long,
        keyId: String,
        ephemeralPublic: String,
    ): String =
        "e2eechat:group-sender-envelope:v1|conversation=$conversationId|sender=$senderUserCode|senderDevice=$senderDeviceId|recipient=$recipientUserCode|recipientDevice=$recipientDeviceId|epoch=$epoch|key=$keyId|eph=$ephemeralPublic"

    private fun envelopeKey(sharedSecret: ByteArray, aad: String): ByteArray =
        hkdfSha256(sharedSecret, "e2eechat:group-sender-envelope:v1:$aad")

    private fun envelopeSignatureData(
        conversationId: Long,
        senderUserCode: String,
        senderDeviceId: String,
        recipientUserCode: String,
        recipientDeviceId: String,
        epoch: Long,
        keyId: String,
        ephemeralPublic: String,
        iv: String,
        ct: String,
    ): ByteArray =
        "e2eechat:group-sender-envelope-sign:v1|conversation=$conversationId|sender=$senderUserCode|senderDevice=$senderDeviceId|recipient=$recipientUserCode|recipientDevice=$recipientDeviceId|epoch=$epoch|key=$keyId|eph=$ephemeralPublic|iv=$iv|ct=$ct"
            .toByteArray(StandardCharsets.UTF_8)

    fun isGroupPayload(payload: String): Boolean =
        runCatching {
            val json = JSONObject(String(fromB64(payload), StandardCharsets.UTF_8))
            json.optString("scheme") == SCHEME
        }.getOrDefault(false)

    private fun ensureLocalSenderKey(
        prefs: ChatPreferences,
        conversationId: Long,
        epoch: Long,
        senderUserCode: String,
    ): Pair<String, String> {
        val deviceId = prefs.deviceId
        val existingRef = runCatching { JSONObject(prefs.cryptoValue(ownKeyRefName(conversationId, epoch))) }.getOrNull()
        val existingKeyId = existingRef?.optString("key_id").orEmpty()
        val existingRaw = existingKeyId.takeIf { it.isNotBlank() }
            ?.let { prefs.cryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, it)) }
            .orEmpty()
        val keyId = existingKeyId.takeIf { existingRaw.isNotBlank() } ?: keyId()
        val senderKey = existingRaw.ifBlank { b64(ByteArray(KEY_BYTES).also(random::nextBytes)) }
        prefs.putCryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, keyId), senderKey)
        prefs.putCryptoValue(ownKeyRefName(conversationId, epoch), JSONObject().put("key_id", keyId).toString())
        return keyId to senderKey
    }

    private fun verifiedGroupDevice(device: GroupKeyDevice): Boolean =
        runCatching {
            device.identityPublicKey.isNotBlank() &&
                device.identityEcdhPublic.isNotBlank() &&
                device.identityEcdhSignature.isNotBlank() &&
                DeviceIdentityManager.verifySignature(
                    device.identityPublicKey,
                    fromB64(device.identityEcdhPublic),
                    device.identityEcdhSignature
                )
        }.getOrDefault(false)

    private fun encryptEnvelope(
        prefs: ChatPreferences,
        conversationId: Long,
        senderUserCode: String,
        senderUsername: String,
        epoch: Long,
        keyId: String,
        senderKey: String,
        recipient: GroupKeyDevice,
    ): GroupSenderKeyEnvelope? {
        if (!verifiedGroupDevice(recipient)) return null
        val senderDeviceId = prefs.deviceId
        val (ephemeralPublic, sharedSecret) = DirectMessageCrypto.ephemeralEcdh(recipient.identityEcdhPublic)
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = envelopeAad(
            conversationId,
            senderUserCode,
            senderDeviceId,
            recipient.userCode,
            recipient.deviceId,
            epoch,
            keyId,
            ephemeralPublic
        )
        val plain = JSONObject()
            .put("sender_key", senderKey)
            .put("sender_user_code", senderUserCode)
            .put("sender_username", senderUsername)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_user_code", recipient.userCode)
            .put("recipient_device_id", recipient.deviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .toString()
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, envelopeKey(sharedSecret, aad), iv, aad)
            .doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = b64(iv)
        val ctB64 = b64(cipherText)
        val signature = DeviceIdentityManager.signWithLocalIdentity(
            prefs,
            envelopeSignatureData(
                conversationId,
                senderUserCode,
                senderDeviceId,
                recipient.userCode,
                recipient.deviceId,
                epoch,
                keyId,
                ephemeralPublic,
                ivB64,
                ctB64
            )
        )
        val wrapped = JSONObject()
            .put("v", 1)
            .put("scheme", ENVELOPE_SCHEME)
            .put("conversation_id", conversationId)
            .put("sender_user_code", senderUserCode)
            .put("sender_device_id", senderDeviceId)
            .put("recipient_user_code", recipient.userCode)
            .put("recipient_device_id", recipient.deviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .put("ephemeral_public", ephemeralPublic)
            .put("iv", ivB64)
            .put("ct", ctB64)
            .put("sig", signature)
            .toString()
        return GroupSenderKeyEnvelope(
            recipientUserCode = recipient.userCode,
            recipientDeviceId = recipient.deviceId,
            wrappedKey = b64(wrapped.toByteArray(StandardCharsets.UTF_8))
        )
    }

    fun buildSenderKeyUpload(
        prefs: ChatPreferences,
        conversationId: Long,
        epoch: Long,
        senderUserCode: String,
        senderUsername: String,
        recipientDevices: List<GroupKeyDevice>,
    ): LocalGroupSenderKey {
        DirectMessageCrypto.localIdentityEcdhPublic(prefs)
        val (keyId, senderKey) = ensureLocalSenderKey(prefs, conversationId, epoch, senderUserCode)
        val envelopes = recipientDevices
            .distinctBy { "${it.userCode}:${it.deviceId}" }
            .mapNotNull { device ->
                encryptEnvelope(
                    prefs = prefs,
                    conversationId = conversationId,
                    senderUserCode = senderUserCode,
                    senderUsername = senderUsername,
                    epoch = epoch,
                    keyId = keyId,
                    senderKey = senderKey,
                    recipient = device
                )
            }
        if (envelopes.isEmpty()) throw IllegalStateException("group_key_devices_unavailable")
        return LocalGroupSenderKey(conversationId, prefs.deviceId, epoch, keyId, envelopes)
    }

    fun importSenderKeys(prefs: ChatPreferences, items: List<GroupSenderKeyBundle>) {
        for (item in items) {
            val envelope = runCatching { JSONObject(String(fromB64(item.wrappedKey), StandardCharsets.UTF_8)) }.getOrNull() ?: continue
            if (envelope.optString("scheme") != ENVELOPE_SCHEME) continue
            val recipientDeviceId = envelope.optString("recipient_device_id")
            if (recipientDeviceId != prefs.deviceId) continue
            val senderUserCode = envelope.optString("sender_user_code")
            val senderDeviceId = envelope.optString("sender_device_id")
            val epoch = envelope.optLong("epoch", 1L)
            val keyId = envelope.optString("key_id")
            val ephemeralPublic = envelope.optString("ephemeral_public")
            val iv = envelope.optString("iv")
            val ct = envelope.optString("ct")
            val signature = envelope.optString("sig")
            if (
                senderUserCode != item.senderUserCode ||
                senderDeviceId != item.deviceId ||
                epoch != item.epoch ||
                keyId != item.keyId ||
                item.senderIdentityPublicKey.isBlank() ||
                ephemeralPublic.isBlank() ||
                iv.isBlank() ||
                ct.isBlank() ||
                signature.isBlank()
            ) continue
            val recipientUserCode = envelope.optString("recipient_user_code")
            val signatureOk = runCatching {
                DeviceIdentityManager.verifySignature(
                    item.senderIdentityPublicKey,
                    envelopeSignatureData(
                        item.conversationId,
                        senderUserCode,
                        senderDeviceId,
                        recipientUserCode,
                        recipientDeviceId,
                        epoch,
                        keyId,
                        ephemeralPublic,
                        iv,
                        ct
                    ),
                    signature
                )
            }.getOrDefault(false)
            if (!signatureOk) continue
            val plain = runCatching {
                val aad = envelopeAad(
                    item.conversationId,
                    senderUserCode,
                    senderDeviceId,
                    recipientUserCode,
                    recipientDeviceId,
                    epoch,
                    keyId,
                    ephemeralPublic
                )
                val sharedSecret = DirectMessageCrypto.ecdhWithLocalIdentity(prefs, ephemeralPublic)
                val decrypted = initCipher(Cipher.DECRYPT_MODE, envelopeKey(sharedSecret, aad), fromB64(iv), aad)
                    .doFinal(fromB64(ct))
                String(decrypted, StandardCharsets.UTF_8)
            }.getOrNull() ?: continue
            val json = runCatching { JSONObject(plain) }.getOrNull() ?: continue
            val senderKey = json.optString("sender_key")
            if (senderKey.isBlank()) continue
            prefs.putCryptoValue(
                senderKeyName(item.conversationId, senderUserCode, senderDeviceId, epoch, keyId),
                senderKey
            )
        }
    }

    fun encryptText(
        prefs: ChatPreferences,
        conversationId: Long,
        senderUserCode: String,
        senderUsername: String,
        epoch: Long,
        plaintext: String,
    ): String {
        val (keyId, senderKey) = ensureLocalSenderKey(prefs, conversationId, epoch, senderUserCode)
        val senderDeviceId = prefs.deviceId
        val counterKey = sendCounterName(conversationId, epoch, keyId)
        val counter = prefs.cryptoValue(counterKey).toLongOrNull() ?: 0L
        val iv = ByteArray(12).also(random::nextBytes)
        val aad = aad(conversationId, senderUserCode, senderDeviceId, epoch, keyId, counter)
        val cipherText = initCipher(Cipher.ENCRYPT_MODE, messageKey(senderKey, counter), iv, aad)
            .doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        prefs.putCryptoValue(counterKey, (counter + 1L).toString())
        val payload = JSONObject()
            .put("v", 1)
            .put("scheme", SCHEME)
            .put("conversation_id", conversationId)
            .put("sender_user_code", senderUserCode)
            .put("sender_username", senderUsername)
            .put("device_id", senderDeviceId)
            .put("epoch", epoch)
            .put("key_id", keyId)
            .put("n", counter)
            .put("iv", b64(iv))
            .put("ct", b64(cipherText))
        return b64(payload.toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptText(prefs: ChatPreferences, conversationId: Long, payloadText: String): String {
        val payload = JSONObject(String(fromB64(payloadText), StandardCharsets.UTF_8))
        require(payload.optString("scheme") == SCHEME) { "not_group_sender_payload" }
        val senderUserCode = payload.getString("sender_user_code")
        val deviceId = payload.getString("device_id")
        val epoch = payload.optLong("epoch", 1L)
        val keyId = payload.getString("key_id")
        val counter = payload.optLong("n", 0L)
        val senderKey = prefs.cryptoValue(senderKeyName(conversationId, senderUserCode, deviceId, epoch, keyId))
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("group_sender_key_unavailable")
        val aad = aad(conversationId, senderUserCode, deviceId, epoch, keyId, counter)
        val plain = initCipher(Cipher.DECRYPT_MODE, messageKey(senderKey, counter), fromB64(payload.getString("iv")), aad)
            .doFinal(fromB64(payload.getString("ct")))
        return String(plain, StandardCharsets.UTF_8)
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

private class ProgressFileRequestBody(
    private val file: File,
    private val contentTypeValue: okhttp3.MediaType,
    private val onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)?,
) : RequestBody() {
    override fun contentType() = contentTypeValue

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                written += read.toLong()
                onProgress?.invoke(written, total)
            }
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

    fun requestRegistration(serverUrl: String, username: String, password: String) {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
        val request = requestBuilder("${base(serverUrl)}/register_request")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
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

    fun registerDeviceIdentity(serverUrl: String, token: String, identity: DeviceIdentityInfo) {
        val body = JSONObject()
            .put("device_id", identity.deviceId)
            .put("platform", "android")
            .put("device_name", identity.deviceName)
            .put("key_alg", identity.keyAlg)
            .put("public_key", identity.publicKey)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/device_identity", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun registerDirectPreKey(serverUrl: String, token: String, preKey: LocalDirectPreKey) {
        val oneTimePrekeys = JSONArray()
        preKey.oneTimePreKeys.forEach { item ->
            oneTimePrekeys.put(
                JSONObject()
                    .put("id", item.id)
                    .put("public_key", item.publicKey)
                    .put("signature", item.signature)
            )
        }
        val body = JSONObject()
            .put("device_id", preKey.deviceId)
            .put("key_alg", preKey.keyAlg)
            .put("identity_ecdh_public", preKey.identityEcdhPublic)
            .put("identity_ecdh_signature", preKey.identityEcdhSignature)
            .put("signed_prekey_public", preKey.signedPrekeyPublic)
            .put("signed_prekey_signature", preKey.signedPrekeySignature)
            .put("one_time_prekeys", oneTimePrekeys)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/direct_prekey", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun deviceIdentities(serverUrl: String, token: String): List<DevicePublicIdentity> {
        execute(authRequest("${base(serverUrl)}/api/device_identities", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DevicePublicIdentity(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    keyAlg = item.optString("key_alg"),
                    publicKey = item.optString("public_key"),
                    updatedAt = item.optLong("updated_at", 0L),
                    lastSeenAt = item.optLong("last_seen_at", 0L)
                )
            }
        }
    }

    fun myDevices(serverUrl: String, token: String): List<DevicePublicIdentity> {
        execute(authRequest("${base(serverUrl)}/api/devices", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DevicePublicIdentity(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    keyAlg = item.optString("key_alg"),
                    publicKey = item.optString("public_key"),
                    updatedAt = item.optLong("updated_at", 0L),
                    lastSeenAt = item.optLong("last_seen_at", 0L)
                )
            }
        }
    }

    fun deleteDevice(serverUrl: String, token: String, deviceId: String, currentDeviceId: String) {
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        val encodedCurrentDeviceId = URLEncoder.encode(currentDeviceId, "UTF-8")
        val request = authRequest("${base(serverUrl)}/api/devices/$encodedDeviceId?current_device_id=$encodedCurrentDeviceId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun directPreKeys(serverUrl: String, token: String, conversationId: Long): List<DirectPreKeyBundle> {
        execute(authRequest("${base(serverUrl)}/api/conversations/$conversationId/direct_prekeys", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                DirectPreKeyBundle(
                    userCode = item.optString("user_code"),
                    username = item.optString("username"),
                    deviceId = item.optString("device_id"),
                    deviceName = item.optString("device_name"),
                    identityKeyAlg = item.optString("identity_key_alg"),
                    identityPublicKey = item.optString("identity_public_key"),
                    identityFingerprint = item.optString("identity_fingerprint"),
                    prekeyAlg = item.optString("prekey_alg"),
                    identityEcdhPublic = item.optString("identity_ecdh_public"),
                    identityEcdhSignature = item.optString("identity_ecdh_signature"),
                    signedPrekeyPublic = item.optString("signed_prekey_public"),
                    signedPrekeySignature = item.optString("signed_prekey_signature"),
                    oneTimePrekeyId = item.optString("one_time_prekey_id"),
                    oneTimePrekeyPublic = item.optString("one_time_prekey_public"),
                    oneTimePrekeySignature = item.optString("one_time_prekey_signature"),
                    updatedAt = item.optLong("updated_at", 0L)
                )
            }
        }
    }

    fun groupSenderKeys(serverUrl: String, token: String, conversationId: Long, deviceId: String): GroupSenderKeyState {
        val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
        execute(authRequest("${base(serverUrl)}/api/conversations/$conversationId/group_sender_keys?device_id=$encodedDeviceId", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val items = json.optJSONArray("items") ?: JSONArray()
            val devices = json.optJSONArray("devices") ?: JSONArray()
            return GroupSenderKeyState(
                epoch = json.optLong("epoch", 1L),
                items = List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    GroupSenderKeyBundle(
                        conversationId = item.optLong("conversation_id", conversationId),
                        senderUserCode = item.optString("sender_user_code"),
                        senderUsername = item.optString("sender_username"),
                        deviceId = item.optString("device_id"),
                        senderDeviceName = item.optString("sender_device_name"),
                        senderIdentityPublicKey = item.optString("sender_identity_public_key"),
                        senderIdentityFingerprint = item.optString("sender_identity_fingerprint"),
                        recipientDeviceId = item.optString("recipient_device_id"),
                        epoch = item.optLong("epoch", 1L),
                        keyId = item.optString("key_id"),
                        wrappedKey = item.optString("wrapped_key"),
                        updatedAt = item.optLong("updated_at", 0L)
                    )
                },
                devices = List(devices.length()) { index ->
                    val item = devices.getJSONObject(index)
                    GroupKeyDevice(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        deviceId = item.optString("device_id"),
                        deviceName = item.optString("device_name"),
                        identityKeyAlg = item.optString("identity_key_alg"),
                        identityPublicKey = item.optString("identity_public_key"),
                        identityFingerprint = item.optString("identity_fingerprint"),
                        prekeyAlg = item.optString("prekey_alg"),
                        identityEcdhPublic = item.optString("identity_ecdh_public"),
                        identityEcdhSignature = item.optString("identity_ecdh_signature"),
                        updatedAt = item.optLong("updated_at", 0L)
                    )
                }
            )
        }
    }

    fun registerGroupSenderKey(serverUrl: String, token: String, key: LocalGroupSenderKey) {
        val envelopes = JSONArray()
        key.envelopes.forEach { envelope ->
            envelopes.put(
                JSONObject()
                    .put("recipient_user_code", envelope.recipientUserCode)
                    .put("recipient_device_id", envelope.recipientDeviceId)
                    .put("wrapped_key", envelope.wrappedKey)
            )
        }
        val body = JSONObject()
            .put("device_id", key.deviceId)
            .put("epoch", key.epoch)
            .put("key_id", key.keyId)
            .put("envelopes", envelopes)
            .toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/${key.conversationId}/group_sender_key", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
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
                    groupCode = item.optString("group_code"),
                    title = item.optString("title"),
                    avatarUrl = item.optString("avatar_url"),
                    directUserCode = item.optString("direct_user_code"),
                    directUsername = item.optString("direct_username"),
                    lastMessageTs = item.optLong("last_message_ts", 0L),
                    lastMessagePreview = item.optString("last_message_preview"),
                    unreadCount = item.optInt("unread_count", 0),
                    lastReadMessageId = item.optLong("last_read_message_id", 0L)
                )
            }
        }
    }

    fun lookupUser(serverUrl: String, token: String, userCode: String): UserLookupResult {
        val encoded = URLEncoder.encode(userCode, StandardCharsets.UTF_8.name())
        execute(authRequest("${base(serverUrl)}/api/contacts/lookup?user_code=$encoded", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val user = json.getJSONObject("user")
            return UserLookupResult(
                user = ChatUser(
                    userCode = user.optString("user_code"),
                    username = user.optString("username"),
                    color = user.optString("color"),
                    avatarUrl = user.optString("avatar_url"),
                    isAdmin = false
                ),
                conversationId = json.optLong("conversation_id", 0L),
                isContact = json.optBoolean("is_contact", false),
                outgoingPending = json.optBoolean("outgoing_pending", false),
                incomingRequestId = json.optLong("incoming_request_id", 0L),
                incomingPending = json.optBoolean("incoming_pending", false)
            )
        }
    }

    fun contactRequests(serverUrl: String, token: String): List<ContactRequestInfo> {
        execute(authRequest("${base(serverUrl)}/api/contact_requests", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                ContactRequestInfo(
                    id = item.optLong("id"),
                    direction = item.optString("direction"),
                    status = item.optString("status"),
                    user = ChatUser(
                        userCode = item.optString("user_code"),
                        username = item.optString("username"),
                        color = item.optString("color"),
                        avatarUrl = item.optString("avatar_url"),
                        isAdmin = false
                    ),
                    createdAt = item.optLong("created_at", 0L)
                )
            }
        }
    }

    fun requestContact(serverUrl: String, token: String, userCode: String): Long {
        val body = JSONObject().put("user_code", userCode).toString()
        val request = authRequest("${base(serverUrl)}/api/contact_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun reviewContactRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/contact_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun createGroup(serverUrl: String, token: String, title: String): Long {
        val body = JSONObject().put("title", title).toString()
        val request = authRequest("${base(serverUrl)}/api/groups", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun lookupGroup(serverUrl: String, token: String, groupCode: String): GroupLookupResult {
        val encoded = URLEncoder.encode(groupCode, StandardCharsets.UTF_8.name())
        execute(authRequest("${base(serverUrl)}/api/groups/lookup?group_code=$encoded", token).get().build()).use { response ->
            val json = JSONObject(response.body!!.string())
            val group = json.getJSONObject("group")
            return GroupLookupResult(
                conversationId = group.optLong("conversation_id", 0L),
                groupCode = group.optString("group_code"),
                title = group.optString("title"),
                avatarUrl = group.optString("avatar_url"),
                isMember = json.optBoolean("is_member", false),
                pending = json.optBoolean("pending", false),
                pendingRequestId = json.optLong("pending_request_id", 0L)
            )
        }
    }

    fun groupJoinRequests(serverUrl: String, token: String): List<GroupJoinRequestInfo> {
        execute(authRequest("${base(serverUrl)}/api/group_join_requests", token).get().build()).use { response ->
            val items = JSONObject(response.body!!.string()).optJSONArray("items") ?: JSONArray()
            return List(items.length()) { index ->
                val item = items.getJSONObject(index)
                GroupJoinRequestInfo(
                    id = item.optLong("id"),
                    direction = item.optString("direction"),
                    status = item.optString("status"),
                    groupCode = item.optString("group_code"),
                    title = item.optString("title"),
                    avatarUrl = item.optString("avatar_url"),
                    requester = ChatUser(
                        userCode = item.optString("requester_user_code"),
                        username = item.optString("requester_username"),
                        color = item.optString("requester_color"),
                        avatarUrl = item.optString("requester_avatar_url"),
                        isAdmin = false
                    ),
                    createdAt = item.optLong("created_at", 0L)
                )
            }
        }
    }

    fun requestJoinGroup(serverUrl: String, token: String, groupCode: String): Long {
        val body = JSONObject().put("group_code", groupCode).toString()
        val request = authRequest("${base(serverUrl)}/api/group_join_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
        }
    }

    fun reviewGroupJoinRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean): Long {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/group_join_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            return json.optLong("conversation_id", 0L)
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

    fun conversationManage(serverUrl: String, token: String, conversationId: Long): ConversationManageInfo {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/manage", token).get().build()
        execute(request).use { response ->
            val json = JSONObject(response.body!!.string())
            val conversation = json.getJSONObject("conversation")
            val membersJson = json.optJSONArray("members") ?: JSONArray()
            val joinRequestsJson = json.optJSONArray("pending_join_requests") ?: JSONArray()
            val adminRequestsJson = json.optJSONArray("pending_admin_requests") ?: JSONArray()
            return ConversationManageInfo(
                id = conversation.optLong("id", conversationId),
                kind = conversation.optString("kind"),
                title = conversation.optString("title"),
                groupCode = conversation.optString("group_code"),
                avatarUrl = conversation.optString("avatar_url"),
                messageTtlMs = conversation.optLong("message_ttl_ms", 0L),
                ownRole = conversation.optString("own_role"),
                canManage = conversation.optBoolean("can_manage", false),
                canManageOwner = conversation.optBoolean("can_manage_owner", false),
                adminCount = conversation.optInt("admin_count", 0),
                adminLimit = conversation.optInt("admin_limit", 3),
                keyEpoch = conversation.optLong("key_epoch", 0L),
                keyDeviceCount = conversation.optInt("key_device_count", 0),
                keyReadyDeviceCount = conversation.optInt("key_ready_device_count", 0),
                members = List(membersJson.length()) { index ->
                    val item = membersJson.getJSONObject(index)
                    GroupMemberInfo(
                        user = ChatUser(
                            userCode = item.optString("user_code"),
                            username = item.optString("username"),
                            color = item.optString("color"),
                            avatarUrl = item.optString("avatar_url"),
                            isAdmin = item.optString("role") == "owner" || item.optString("role") == "admin"
                        ),
                        role = item.optString("role", "member"),
                        joinedAt = item.optLong("joined_at", 0L)
                    )
                },
                pendingJoinRequests = List(joinRequestsJson.length()) { index ->
                    val item = joinRequestsJson.getJSONObject(index)
                    GroupJoinRequestInfo(
                        id = item.optLong("id"),
                        direction = item.optString("direction"),
                        status = item.optString("status"),
                        groupCode = item.optString("group_code"),
                        title = item.optString("title"),
                        avatarUrl = item.optString("avatar_url"),
                        requester = ChatUser(
                            userCode = item.optString("requester_user_code"),
                            username = item.optString("requester_username"),
                            color = item.optString("requester_color"),
                            avatarUrl = item.optString("requester_avatar_url"),
                            isAdmin = false
                        ),
                        createdAt = item.optLong("created_at", 0L)
                    )
                },
                pendingAdminRequests = List(adminRequestsJson.length()) { index ->
                    val item = adminRequestsJson.getJSONObject(index)
                    GroupAdminRequestInfo(
                        id = item.optLong("id"),
                        conversationId = item.optLong("conversation_id", conversationId),
                        status = item.optString("status"),
                        requesterUserCode = item.optString("requester_user_code"),
                        requesterUsername = item.optString("requester_username"),
                        targetUserCode = item.optString("target_user_code"),
                        targetUsername = item.optString("target_username"),
                        createdAt = item.optLong("created_at", 0L)
                    )
                }
            )
        }
    }

    fun clearConversationHistory(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/clear_history", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun changeGroupTitle(serverUrl: String, token: String, conversationId: Long, title: String) {
        val body = JSONObject().put("title", title).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/title", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun setGroupExpiration(serverUrl: String, token: String, conversationId: Long, ttlMs: Long) {
        val body = JSONObject().put("message_ttl_ms", ttlMs).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/expiration", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun removeGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/members/$userCode/remove", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun addGroupMember(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/members/$userCode/add", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun transferGroupOwner(serverUrl: String, token: String, conversationId: Long, targetUserCode: String) {
        val body = JSONObject().put("target_user_code", targetUserCode).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/transfer_owner", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun requestGroupAdmin(serverUrl: String, token: String, conversationId: Long, targetUserCode: String): String {
        val body = JSONObject().put("target_user_code", targetUserCode).toString()
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/admin_requests", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).use { response ->
            return JSONObject(response.body!!.string()).optString("status", "pending")
        }
    }

    fun removeGroupAdmin(serverUrl: String, token: String, conversationId: Long, userCode: String) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/admins/$userCode/remove", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun reviewGroupAdminRequest(serverUrl: String, token: String, requestId: Long, approve: Boolean) {
        val action = if (approve) "approve" else "reject"
        val request = authRequest("${base(serverUrl)}/api/group_admin_requests/$requestId/$action", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
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

    fun deleteDirectConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun deleteGroupConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId", token)
            .delete()
            .build()
        execute(request).close()
    }

    fun leaveGroupConversation(serverUrl: String, token: String, conversationId: Long) {
        val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/leave", token)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        execute(request).close()
    }

    fun requestAccountDeletion(serverUrl: String, token: String) {
        val request = authRequest("${base(serverUrl)}/api/account_deletion_request", token)
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

    fun uploadConversationAvatar(
        serverUrl: String,
        token: String,
        conversationId: Long,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): String {
        val tempFile = File.createTempFile("group_avatar_", fileName.substringAfterLast('.', "jpg"))
        tempFile.writeBytes(bytes)
        return try {
            val avatarBody = ProgressRequestBody(tempFile.asRequestBody(mime.toMediaType()), onProgress)
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("avatar", fileName, avatarBody)
                .build()
            val request = authRequest("${base(serverUrl)}/api/conversations/$conversationId/avatar", token)
                .post(form)
                .build()
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
        encryptedFile: File,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ): ChatMessage {
        val fileBody = ProgressFileRequestBody(
            encryptedFile,
            "application/octet-stream".toMediaType(),
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

    fun downloadToFile(
        serverUrl: String,
        path: String,
        targetFile: File,
        onProgress: ((bytesDone: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val absolute = if (path.startsWith("http")) path else "${base(serverUrl)}$path"
        targetFile.parentFile?.mkdirs()
        execute(requestBuilder(absolute).get().build()).use { response ->
            val body = response.body ?: return
            val total = body.contentLength().coerceAtLeast(0L)
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            try {
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read.toLong()
                            onProgress?.invoke(done, total)
                        }
                    }
                }
            } catch (error: Throwable) {
                targetFile.delete()
                throw error
            }
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

fun decryptAttachmentFileKey(message: ChatMessage, prefs: ChatPreferences?): String? {
    val payload = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
    if (payload.optInt("v", 0) < 3 || !payload.has("key_wrap")) return null
    val wrap = payload.optJSONObject("key_wrap") ?: return null
    val wrappedPayload = wrap.optString("payload")
    val plain = when (wrap.optString("scheme")) {
        "direct-dr" -> prefs?.let { DirectMessageCrypto.decryptText(it, message.conversationId, wrappedPayload) }
        "group-sender" -> prefs?.let { GroupSenderKeyCrypto.decryptText(it, message.conversationId, wrappedPayload) }
        else -> null
    } ?: return null
    return JSONObject(plain).optString("file_key").takeIf { it.isNotBlank() }
}

fun decryptAttachmentMeta(message: ChatMessage, prefs: ChatPreferences?): JSONObject? {
    val payload = runCatching { JSONObject(message.payload) }.getOrNull() ?: return null
    return if (payload.optInt("v", 0) >= 3 && payload.has("key_wrap")) {
        val fileKey = decryptAttachmentFileKey(message, prefs) ?: return null
        JSONObject(
            ChatCrypto.decryptTextWithKey(
                fileKey,
                payload.getString("meta"),
                ChatCrypto.attachmentMetaAad(message.conversationId, message.kind)
            )
        )
    } else {
        null
    }
}

fun resolveAttachment(
    context: Context,
    api: ChatApi,
    serverUrl: String,
    message: ChatMessage,
    prefs: ChatPreferences? = null,
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
        decryptAttachmentMeta(message, prefs) ?: throw IllegalStateException("Unable to decrypt attachment")
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
    val declaredSize = meta.optLong("size", 0L)
    val cacheFile = attachmentCacheFile(context, message.id, fileName)
    if (cacheFile.exists()) {
        return DecryptedAttachment(
            name = meta.optString("name").ifBlank { fileName },
            mime = mime,
            size = declaredSize.takeIf { it > 0L } ?: cacheFile.length(),
            file = cacheFile,
            uri = if (exportToDownloads) cachedAttachmentUri(context, message.id, fileName) else null
        )
    }

    if (isEncryptedAttachment) {
        val enc = payload.getJSONObject("enc")
        val encryptedTemp = File.createTempFile("download_", ".enc", context.cacheDir)
        try {
            api.downloadToFile(serverUrl, attachment!!.getString("url"), encryptedTemp, onProgress)
            val fileKey = decryptAttachmentFileKey(message, prefs)
                ?: throw IllegalStateException("Unable to decrypt attachment key")
            ChatCrypto.decryptBinaryFileWithKey(
                fileKey = fileKey,
                iv = enc.getString("iv"),
                sourceFile = encryptedTemp,
                targetFile = cacheFile,
                aad = ChatCrypto.attachmentFileAad(message.conversationId, message.kind)
            )
        } finally {
            encryptedTemp.delete()
        }
    } else {
        val downloadUrl = attachment?.optString("url").orEmpty().ifBlank { payload.optString("url") }
        require(downloadUrl.isNotBlank()) { "Bad attachment" }
        api.downloadToFile(serverUrl, downloadUrl, cacheFile, onProgress)
    }
    val plainBytesForExport = if (exportToDownloads) cacheFile.readBytes() else ByteArray(0)
    val downloadUri = if (exportToDownloads) {
        runCatching {
            ensureAttachmentInDownloads(context, message.id, fileName, mime, plainBytesForExport)
        }.getOrNull()
    } else {
        null
    }
    return DecryptedAttachment(
        name = meta.optString("name").ifBlank { fileName },
        mime = mime,
        size = declaredSize.takeIf { it > 0L } ?: cacheFile.length(),
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
