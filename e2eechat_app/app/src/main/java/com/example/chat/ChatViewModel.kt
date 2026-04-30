package com.example.chat

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class ChatUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val e2eeEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val displayMode: AppDisplayMode = AppDisplayMode.SYSTEM,
    val dynamicColorsEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isConnected: Boolean = false,
    val me: ChatUser? = null,
    val deviceIdentity: DeviceIdentityInfo? = null,
    val deviceIdentities: List<DevicePublicIdentity> = emptyList(),
    val myDevices: List<DevicePublicIdentity> = emptyList(),
    val users: List<ChatUser> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val contactRequests: List<ContactRequestInfo> = emptyList(),
    val groupJoinRequests: List<GroupJoinRequestInfo> = emptyList(),
    val userLookupResult: UserLookupResult? = null,
    val groupLookupResult: GroupLookupResult? = null,
    val currentConversationManage: ConversationManageInfo? = null,
    val relationshipMessage: String? = null,
    val currentConversationId: Long = 0L,
    val currentConversationDeliveryStates: Map<String, Long> = emptyMap(),
    val currentConversationReadStates: Map<String, Long> = emptyMap(),
    val messages: List<ChatMessage> = emptyList(),
    val isRefreshing: Boolean = false,
    val uploadProgress: TransferProgress? = null,
    val downloadProgress: TransferProgress? = null,
    val latestAppRelease: AppReleaseInfo? = null,
    val downloadedUpdate: DecryptedAttachment? = null,
    val isCheckingUpdate: Boolean = false,
    val updateStatus: String? = null,
    val latestPrerelease: AppReleaseInfo? = null,
    val downloadedPrerelease: DecryptedAttachment? = null,
    val isCheckingPrerelease: Boolean = false,
    val prereleaseStatus: String? = null,
    val registrationMessage: String? = null,
    val voiceCall: VoiceCallUiState = VoiceCallUiState(),
    val error: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private data class BootstrapSnapshot(
        val me: ChatUser,
        val users: List<ChatUser>,
        val conversations: List<ConversationSummary>,
        val deviceIdentities: List<DevicePublicIdentity>,
        val myDevices: List<DevicePublicIdentity>,
        val contactRequests: List<ContactRequestInfo>,
        val groupJoinRequests: List<GroupJoinRequestInfo>,
    )

    private data class ConversationCache(
        val messages: List<ChatMessage> = emptyList(),
        val deliveryStates: Map<String, Long> = emptyMap(),
        val readStates: Map<String, Long> = emptyMap(),
    )

    private data class RefreshSnapshot(
        val me: ChatUser,
        val users: List<ChatUser>,
        val conversations: List<ConversationSummary>,
        val deviceIdentities: List<DevicePublicIdentity>,
        val myDevices: List<DevicePublicIdentity>,
        val contactRequests: List<ContactRequestInfo>,
        val groupJoinRequests: List<GroupJoinRequestInfo>,
        val deliveryStates: Map<String, Long>,
        val readStates: Map<String, Long>,
        val messages: List<ChatMessage>,
    )

    private sealed interface PendingAction {
        data class Text(
            val text: String,
            val reply: ReplyPreview?,
        ) : PendingAction

        data class Attachment(
            val kind: String,
            val fileName: String,
            val mime: String,
            val localFilePath: String,
            val reply: ReplyPreview?,
            val durationMs: Long,
        ) : PendingAction
    }

    private data class PendingOutgoing(
        val action: PendingAction,
        val message: ChatMessage,
    )

    private val app = application
    private val prefs = ChatPreferences(application)
    private val api = ChatApi()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var refreshJob: Job? = null
    private var sessionRecoveryJob: Job? = null
    private var reconnectAttempts = 0
    private var allowReconnect = false
    private var lastForegroundRecoveryAt = 0L
    private var lastSocketActivityAt = 0L
    private var isAppInForeground = true
    private var foregroundPresenceJob: Job? = null
    private val conversationCaches = mutableMapOf<Long, ConversationCache>()
    private val pendingOutgoing = mutableMapOf<Long, PendingOutgoing>()
    private val pendingTimeoutJobs = mutableMapOf<Long, Job>()
    private var clearVoiceCallJob: Job? = null
    private var nextLocalMessageId = -1L
    private var pendingIncomingCallAction: String? = null

    var uiState by mutableStateOf(
        ChatUiState(
            serverUrl = prefs.serverUrl,
            e2eeEnabled = prefs.e2eeEnabled,
            language = AppLanguage.fromStored(prefs.language),
            displayMode = AppDisplayMode.fromStored(prefs.displayMode),
            dynamicColorsEnabled = prefs.dynamicColorsEnabled
        )
    )
        private set

    private val voiceCallController = object : VoiceCallRuntime.Controller {
        override fun accept() = acceptIncomingCall()
        override fun decline() = declineIncomingCall()
        override fun end() = endVoiceCall()
        override fun toggleMute() = toggleVoiceCallMute()
        override fun toggleSpeaker() = toggleVoiceCallSpeaker()
        override fun selectAudioRoute(route: VoiceAudioRoute) = setVoiceCallAudioRoute(route)
        override fun syncAudioRouteFromSystem(route: VoiceAudioRoute) = syncVoiceCallAudioRouteFromSystem(route)
        override fun dismissTerminal() = dismissVoiceCallStatus()
    }

    private val callManager = VoiceCallManager(application.applicationContext, object : VoiceCallManager.Callbacks {
        override fun onSignal(type: String, payload: JSONObject) {
            viewModelScope.launch {
                socket?.takeIf { uiState.isConnected }?.send(payload.put("type", type).toString())
            }
        }

        override fun onStateChanged(state: VoiceCallUiState) {
            viewModelScope.launch { applyVoiceCallState(state) }
        }

        override fun onError(message: String) {
            viewModelScope.launch {
                uiState = uiState.copy(error = message)
            }
        }
    })

    init {
        VoiceCallRuntime.attach(voiceCallController)
        restoreVoiceCallStateFromRuntime()
        if (VoiceCallRuntime.state.value.phase == VoiceCallPhase.IDLE) {
            VoiceCallRuntime.updateState(app, uiState.voiceCall)
        }
        cleanupInstalledUpdatePackages()
        when {
            prefs.token.isNotBlank() -> restoreSession()
            prefs.savedLoginUsername.isNotBlank() && prefs.savedLoginPassword.isNotBlank() -> autoLoginFromSaved()
        }
    }

    fun updateE2eeEnabled(value: Boolean) {
        prefs.e2eeEnabled = value
        uiState = uiState.copy(e2eeEnabled = value)
    }

    fun updateLanguage(value: AppLanguage) {
        prefs.language = value.name
        uiState = uiState.copy(language = value)
    }

    fun updateDisplayMode(value: AppDisplayMode) {
        prefs.displayMode = value.name
        uiState = uiState.copy(displayMode = value)
    }

    fun updateDynamicColorsEnabled(value: Boolean) {
        prefs.dynamicColorsEnabled = value
        uiState = uiState.copy(dynamicColorsEnabled = value)
    }

    private fun localizeCallStatus(key: String): String {
        val strings = stringsFor(uiState.language)
        return when (key) {
            "calling" -> strings.calling
            "incoming" -> strings.incomingCall
            "connecting" -> strings.callConnecting
            "reconnecting" -> strings.callReconnecting
            "active" -> strings.callActive
            "ended" -> strings.callEnded
            "rejected" -> strings.callRejected
            "busy" -> strings.callBusy
            "unavailable" -> strings.callUnavailable
            "connection_lost" -> strings.callConnectionLost
            "failed" -> strings.callFailed
            else -> key
        }
    }

    private fun Throwable?.isTransientNetworkIssue(): Boolean {
        if (this == null) return false
        return generateSequence(this) { it.cause }.any { error ->
            error is UnknownHostException ||
                error is SocketTimeoutException ||
                error is ConnectException ||
                (error is IOException && (
                    error.message?.contains("resolve host", ignoreCase = true) == true ||
                        error.message?.contains("no address associated", ignoreCase = true) == true ||
                        error.message?.contains("failed to connect", ignoreCase = true) == true
                    ))
        }
    }

    private fun Throwable?.isSessionAuthFailure(): Boolean {
        if (this == null) return false
        return generateSequence(this) { it.cause }.any { error ->
            val message = error.message?.trim()?.lowercase()
            message == "session_expired" ||
                message == "unauthorized" ||
                message?.contains("jwt expired") == true ||
                message?.contains("token expired") == true
        }
    }

    private fun recoverExpiredSession(reason: String = "Session expired") {
        if (sessionRecoveryJob?.isActive == true) return
        allowReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        sessionRecoveryJob = viewModelScope.launch {
            if (!trySavedLogin()) {
                logout(reason)
            }
        }
    }

    private fun applyVoiceCallState(state: VoiceCallUiState, syncRuntime: Boolean = true) {
        clearVoiceCallJob?.cancel()
        val localizedState = state.copy(statusMessage = localizeCallStatus(state.statusMessage))
        uiState = uiState.copy(voiceCall = localizedState)
        if (localizedState.phase == VoiceCallPhase.INCOMING && localizedState.conversationId > 0L && localizedState.peerUsername.isNotBlank()) {
            prefs.setPendingIncomingCall(
                PendingIncomingCallInvite(
                    conversationId = localizedState.conversationId,
                    peerUserCode = localizedState.peerUserCode,
                    peerUsername = localizedState.peerUsername,
                    createdAt = System.currentTimeMillis(),
                )
            )
        } else {
            prefs.setPendingIncomingCall(null)
            NotificationCenter.clearIncomingCallNotification(app)
        }
        if (syncRuntime) {
            VoiceCallRuntime.updateState(app, localizedState)
        }
        if (localizedState.phase == VoiceCallPhase.ENDED || localizedState.phase == VoiceCallPhase.FAILED) {
            clearVoiceCallJob = viewModelScope.launch {
                delay(6_000L)
                dismissVoiceCallStatus()
            }
        }
    }

    private fun restoreVoiceCallStateFromRuntime() {
        val runtimeState = VoiceCallRuntime.state.value
        if (runtimeState.phase == VoiceCallPhase.IDLE) return
        val shouldRestore =
            uiState.voiceCall.phase == VoiceCallPhase.IDLE ||
                uiState.voiceCall.conversationId != runtimeState.conversationId ||
                uiState.voiceCall.phase != runtimeState.phase
        if (shouldRestore) {
            applyVoiceCallState(runtimeState, syncRuntime = false)
        }
        if (runtimeState.phase == VoiceCallPhase.INCOMING) {
            callManager.restoreIncomingInvite(runtimeState)
        }
    }

    private fun restorePendingIncomingCallIfNeeded() {
        val pending = prefs.pendingIncomingCall() ?: return
        val ageMs = System.currentTimeMillis() - pending.createdAt
        if (ageMs > 35_000L) {
            prefs.setPendingIncomingCall(null)
            NotificationCenter.clearIncomingCallNotification(app)
            return
        }
        callManager.restoreIncomingInvite(
            VoiceCallUiState(
                phase = VoiceCallPhase.INCOMING,
                conversationId = pending.conversationId,
                peerUserCode = pending.peerUserCode,
                peerUsername = pending.peerUsername,
                isIncoming = true,
                statusMessage = "incoming"
            )
        )
        if (uiState.currentConversationId != pending.conversationId && uiState.conversations.any { it.id == pending.conversationId }) {
            openConversation(pending.conversationId, forceRefresh = true)
        }
    }

    private fun performPendingIncomingCallActionIfNeeded() {
        val action = pendingIncomingCallAction ?: return
        if (uiState.voiceCall.phase != VoiceCallPhase.INCOMING) {
            pendingIncomingCallAction = null
            return
        }
        pendingIncomingCallAction = null
        when (action) {
            "accept" -> callManager.acceptIncoming()
            "reject" -> callManager.rejectIncoming()
        }
    }

    fun changeUsername(newUsername: String) {
        viewModelScope.launch {
            val trimmed = newUsername.trim()
            val strings = stringsFor(uiState.language)
            val previousMe = uiState.me
            if (trimmed.isBlank()) {
                uiState = uiState.copy(error = strings.usernameEmpty)
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    api.changeUsername(uiState.serverUrl, prefs.token, trimmed)
                }
            }.onSuccess { updatedMe ->
                prefs.username = updatedMe.username
                val savedPassword = prefs.savedLoginPassword
                if (savedPassword.isNotBlank()) {
                    prefs.saveLogin(updatedMe.username, savedPassword)
                }
                val updatedMessages = rewriteOwnMessagesIdentity(
                    messages = uiState.messages,
                    userCode = updatedMe.userCode,
                    oldUsername = previousMe?.username.orEmpty(),
                    newUsername = updatedMe.username
                )
                rewriteCachedOwnMessagesIdentity(
                    userCode = updatedMe.userCode,
                    oldUsername = previousMe?.username.orEmpty(),
                    newUsername = updatedMe.username
                )
                uiState = uiState.copy(me = updatedMe, messages = updatedMessages, error = null)
                refreshNow(reconnectIfNeeded = false, showIndicator = false)
            }.onFailure {
                val message = when (it.message) {
                    "invalid_username" -> strings.invalidUsername
                    "username_taken" -> strings.usernameTaken
                    else -> strings.unableToChangeUsername
                }
                uiState = uiState.copy(error = message)
            }
        }
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    fun reportError(message: String) {
        uiState = uiState.copy(error = message)
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, registrationMessage = null, error = null)
            runCatching {
                loginWithCredentials(username.trim(), password)
            }.onFailure {
                uiState = uiState.copy(isLoading = false, error = it.message ?: "Login failed")
            }
        }
    }

    fun requestRegistration(username: String, password: String, confirmPassword: String) {
        viewModelScope.launch {
            val strings = stringsFor(uiState.language)
            val trimmed = username.trim()
            if (trimmed.isBlank()) {
                uiState = uiState.copy(error = strings.usernameEmpty, registrationMessage = null)
                return@launch
            }
            if (password.length < 8) {
                uiState = uiState.copy(error = strings.passwordTooShort, registrationMessage = null)
                return@launch
            }
            if (password != confirmPassword) {
                uiState = uiState.copy(error = strings.passwordsDoNotMatch, registrationMessage = null)
                return@launch
            }
            uiState = uiState.copy(isLoading = true, error = null, registrationMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.requestRegistration(uiState.serverUrl, trimmed, password) }
            }.onSuccess {
                uiState = uiState.copy(isLoading = false, registrationMessage = strings.registrationPending, error = null)
            }.onFailure {
                val message = when (it.message) {
                    "invalid_registration_data" -> strings.invalidUsername
                    "username_taken" -> strings.usernameTaken
                    "request_already_pending" -> strings.requestAlreadyPending
                    else -> strings.unableToRegister
                }
                uiState = uiState.copy(isLoading = false, registrationMessage = null, error = message)
            }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            runCatching { loadBootstrap() }.onFailure { error ->
                if (error.isSessionAuthFailure()) {
                    recoverExpiredSession("Session expired")
                } else {
                    uiState = uiState.copy(isLoading = false, error = error.message ?: "Unable to reconnect")
                }
            }
        }
    }

    private fun autoLoginFromSaved() {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, error = null)
            if (!trySavedLogin()) {
                uiState = uiState.copy(isLoading = false)
            }
        }
    }

    private suspend fun loadBootstrap() {
        val token = prefs.token
        val serverUrl = uiState.serverUrl
        val bootstrap = withContext(Dispatchers.IO) {
            coroutineScope {
                val meDeferred = async { api.me(serverUrl, token) }
                val usersDeferred = async { api.users(serverUrl, token) }
                val conversationsDeferred = async { api.conversations(serverUrl, token) }
                val deviceIdentitiesDeferred = async { api.deviceIdentities(serverUrl, token) }
                val myDevicesDeferred = async { api.myDevices(serverUrl, token) }
                val contactRequestsDeferred = async { api.contactRequests(serverUrl, token) }
                val groupJoinRequestsDeferred = async { api.groupJoinRequests(serverUrl, token) }
                BootstrapSnapshot(
                    me = meDeferred.await(),
                    users = usersDeferred.await(),
                    conversations = conversationsDeferred.await(),
                    deviceIdentities = deviceIdentitiesDeferred.await(),
                    myDevices = myDevicesDeferred.await(),
                    contactRequests = contactRequestsDeferred.await(),
                    groupJoinRequests = groupJoinRequestsDeferred.await()
                )
            }
        }

        val defaultConversationId = bootstrap.conversations.firstOrNull()?.id ?: 0L
        prefs.userCode = bootstrap.me.userCode
        prefs.username = bootstrap.me.username
        uiState = uiState.copy(
            isLoading = false,
            isLoggedIn = true,
            isConnected = false,
            me = bootstrap.me,
            users = bootstrap.users,
            conversations = bootstrap.conversations,
            deviceIdentities = bootstrap.deviceIdentities,
            myDevices = bootstrap.myDevices,
            contactRequests = bootstrap.contactRequests,
            groupJoinRequests = bootstrap.groupJoinRequests,
            userLookupResult = null,
            groupLookupResult = null,
            relationshipMessage = null,
            currentConversationId = defaultConversationId,
            currentConversationDeliveryStates = emptyMap(),
            currentConversationReadStates = emptyMap(),
            messages = emptyList(),
            error = null
        )
        allowReconnect = true
        reconnectAttempts = 0
        connectSocket()
        scheduleNotifications()
        FcmRegistrar(app).registerCurrentTokenIfAvailable()
        registerCurrentDeviceIdentity(bootstrap.me)
        restorePendingIncomingCallIfNeeded()
        if (defaultConversationId > 0) {
            openConversation(defaultConversationId, forceRefresh = true)
        }
        primeConversationCaches(bootstrap.conversations, excludingConversationId = defaultConversationId)
    }

    private fun registerCurrentDeviceIdentity(me: ChatUser) {
        val token = prefs.token
        val serverUrl = uiState.serverUrl
        if (token.isBlank()) return
        viewModelScope.launch {
            val localIdentity = runCatching {
                withContext(Dispatchers.IO) {
                    DeviceIdentityManager.localIdentity(prefs, me.userCode.ifBlank { me.username })
                }
            }.getOrNull()
            if (localIdentity != null) {
                uiState = uiState.copy(deviceIdentity = localIdentity)
            }
            runCatching {
                val identity = localIdentity ?: withContext(Dispatchers.IO) {
                    DeviceIdentityManager.localIdentity(prefs, me.userCode.ifBlank { me.username })
                }
                withContext(Dispatchers.IO) {
                    api.registerDeviceIdentity(serverUrl, token, identity)
                    api.registerDirectPreKey(serverUrl, token, DirectMessageCrypto.ensureLocalPreKey(prefs))
                }
                val (identities, myDevices) = withContext(Dispatchers.IO) {
                    api.deviceIdentities(serverUrl, token) to api.myDevices(serverUrl, token)
                }
                uiState = uiState.copy(deviceIdentities = identities, myDevices = myDevices)
                identity
            }.onSuccess { identity ->
                uiState = uiState.copy(deviceIdentity = identity)
            }
        }
    }

    private suspend fun ensureGroupSenderKeys(conversationId: Long, uploadOwnKey: Boolean = true): Long {
        val me = uiState.me ?: return 1L
        var state = withContext(Dispatchers.IO) {
            api.groupSenderKeys(uiState.serverUrl, prefs.token, conversationId, prefs.deviceId)
        }
        withContext(Dispatchers.Default) {
            GroupSenderKeyCrypto.importSenderKeys(prefs, state.items)
        }
        repeat(if (uploadOwnKey) 2 else 0) {
            val localKey = withContext(Dispatchers.Default) {
                GroupSenderKeyCrypto.buildSenderKeyUpload(
                    prefs,
                    conversationId,
                    state.epoch,
                    me.userCode.ifBlank { me.username },
                    me.username,
                    state.devices
                )
            }
            val posted = runCatching {
                withContext(Dispatchers.IO) {
                    api.registerGroupSenderKey(uiState.serverUrl, prefs.token, localKey)
                }
            }.isSuccess
            if (posted) return state.epoch
            state = withContext(Dispatchers.IO) {
                api.groupSenderKeys(uiState.serverUrl, prefs.token, conversationId, prefs.deviceId)
            }
            withContext(Dispatchers.Default) {
                GroupSenderKeyCrypto.importSenderKeys(prefs, state.items)
            }
        }
        return state.epoch
    }

    private suspend fun loginWithCredentials(username: String, password: String) {
        val result = withContext(Dispatchers.IO) { api.login(uiState.serverUrl, username, password) }
        prefs.token = result.token
        prefs.userCode = result.userCode
        prefs.username = result.username
        prefs.saveLogin(result.username, password)
        prefs.lastNotifiedMessageId = 0L
        loadBootstrap()
    }

    private suspend fun trySavedLogin(): Boolean {
        val username = prefs.savedLoginUsername.trim()
        val password = prefs.savedLoginPassword
        if (username.isBlank() || password.isBlank()) return false
        return runCatching {
            loginWithCredentials(username, password)
        }.isSuccess
    }

    fun openConversation(conversationId: Long, forceRefresh: Boolean = false) {
        if (conversationId <= 0) return
        val cached = conversationCaches[conversationId]
        if (!forceRefresh && uiState.currentConversationId == conversationId && (uiState.messages.isNotEmpty() || cached != null)) return
        uiState = uiState.copy(
            isLoading = cached?.messages.isNullOrEmpty(),
            currentConversationId = conversationId,
            currentConversationDeliveryStates = cached?.deliveryStates ?: emptyMap(),
            currentConversationReadStates = cached?.readStates ?: emptyMap(),
            messages = cached?.messages ?: emptyList(),
            currentConversationManage = null,
            error = null
        )
        viewModelScope.launch {
            runCatching {
                fetchConversationCache(conversationId)
            }.onSuccess { snapshot ->
                if (uiState.conversations.find { it.id == conversationId }?.kind == "group") {
                    runCatching { ensureGroupSenderKeys(conversationId, uploadOwnKey = false) }
                }
                conversationCaches[conversationId] = snapshot
                uiState = uiState.copy(
                    isLoading = false,
                    currentConversationId = conversationId,
                    currentConversationDeliveryStates = snapshot.deliveryStates,
                    currentConversationReadStates = snapshot.readStates,
                    messages = snapshot.messages,
                    error = null
                )
                markDeliveredLatest()
                markReadLatest()
                refreshConversationSummaries()
            }.onFailure {
                uiState = uiState.copy(isLoading = false, error = it.message ?: "Unable to load conversation")
            }
        }
    }

    private suspend fun fetchConversationCache(conversationId: Long): ConversationCache =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val historyDeferred = async {
                    api.history(uiState.serverUrl, prefs.token, conversationId = conversationId, limit = 200)
                }
                val deliveriesDeferred = async {
                    runCatching {
                        api.conversationDeliveryStates(uiState.serverUrl, prefs.token, conversationId)
                            .associate { identityKey(it.userCode, it.username) to it.lastDeliveredMessageId }
                    }.getOrDefault(emptyMap())
                }
                val readsDeferred = async {
                    runCatching {
                        api.conversationReadStates(uiState.serverUrl, prefs.token, conversationId)
                            .associate { identityKey(it.userCode, it.username) to it.lastReadMessageId }
                    }.getOrDefault(emptyMap())
                }
                ConversationCache(
                    messages = mergeMessages(
                        historyDeferred.await().items
                            .filter { it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis() },
                        pendingMessagesForConversation(conversationId)
                    ),
                    deliveryStates = deliveriesDeferred.await(),
                    readStates = readsDeferred.await()
                )
            }
        }

    private fun primeConversationCaches(conversations: List<ConversationSummary>, excludingConversationId: Long) {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            conversations
                .asSequence()
                .map { it.id }
                .filter { it > 0L && it != excludingConversationId && conversationCaches[it] == null }
                .take(4)
                .forEach { conversationId ->
                    runCatching { fetchConversationCache(conversationId) }
                        .onSuccess { conversationCaches[conversationId] = it }
                }
        }
    }

    private fun connectSocket() {
        reconnectJob?.cancel()
        socket?.close(1000, null)
        val webSocket = api.connect(uiState.serverUrl, prefs.token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                viewModelScope.launch {
                    lastSocketActivityAt = System.currentTimeMillis()
                    reconnectAttempts = 0
                    uiState = uiState.copy(isConnected = true, error = null)
                    callManager.onSignalingReconnected()
                    refreshCallConfig()
                    syncSocketPresence()
                    markDeliveredLatest()
                    markReadLatest()
                    refreshNow(reconnectIfNeeded = false, showIndicator = false)
                    performPendingIncomingCallActionIfNeeded()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                lastSocketActivityAt = System.currentTimeMillis()
                viewModelScope.launch { handleSocketEvent(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                viewModelScope.launch { handleSocketDisconnected(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                viewModelScope.launch { handleSocketDisconnected(webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                viewModelScope.launch { handleSocketDisconnected(webSocket) }
            }
        })
        socket = webSocket
    }

    private fun handleSocketDisconnected(disconnectedSocket: WebSocket) {
        if (socket === disconnectedSocket) {
            socket = null
        }
        foregroundPresenceJob?.cancel()
        foregroundPresenceJob = null
        lastSocketActivityAt = System.currentTimeMillis()
        callManager.onSignalingDisconnected()
        uiState = uiState.copy(isConnected = false)
        scheduleReconnect()
    }

    private fun reconnectSocketNow() {
        reconnectJob?.cancel()
        reconnectJob = null
        if (!allowReconnect || prefs.token.isBlank()) return
        connectSocket()
    }

    private fun scheduleReconnect() {
        if (!allowReconnect || prefs.token.isBlank()) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = viewModelScope.launch {
            while (allowReconnect && prefs.token.isNotBlank() && !uiState.isConnected) {
                reconnectAttempts += 1
                val delayMs = (2_000L * reconnectAttempts.coerceAtMost(6)).coerceAtMost(15_000L)
                delay(delayMs)
                if (!allowReconnect || prefs.token.isBlank() || uiState.isConnected) return@launch
                val result = runCatching {
                    val bootstrap = withContext(Dispatchers.IO) {
                        coroutineScope {
                            val meDeferred = async { api.me(uiState.serverUrl, prefs.token) }
                            val usersDeferred = async { api.users(uiState.serverUrl, prefs.token) }
                            val conversationsDeferred = async { api.conversations(uiState.serverUrl, prefs.token) }
                            val deviceIdentitiesDeferred = async { api.deviceIdentities(uiState.serverUrl, prefs.token) }
                            val myDevicesDeferred = async { api.myDevices(uiState.serverUrl, prefs.token) }
                            val contactRequestsDeferred = async { api.contactRequests(uiState.serverUrl, prefs.token) }
                            val groupJoinRequestsDeferred = async { api.groupJoinRequests(uiState.serverUrl, prefs.token) }
                            BootstrapSnapshot(
                                me = meDeferred.await(),
                                users = usersDeferred.await(),
                                conversations = conversationsDeferred.await(),
                                deviceIdentities = deviceIdentitiesDeferred.await(),
                                myDevices = myDevicesDeferred.await(),
                                contactRequests = contactRequestsDeferred.await(),
                                groupJoinRequests = groupJoinRequestsDeferred.await()
                            )
                        }
                    }
                    uiState = uiState.copy(
                        me = bootstrap.me,
                        users = bootstrap.users,
                        conversations = bootstrap.conversations,
                        deviceIdentities = bootstrap.deviceIdentities,
                        myDevices = bootstrap.myDevices,
                        contactRequests = bootstrap.contactRequests,
                        groupJoinRequests = bootstrap.groupJoinRequests,
                        error = null
                    )
                    connectSocket()
                }
                val error = result.exceptionOrNull()
                if (error.isSessionAuthFailure()) {
                    recoverExpiredSession("Session expired")
                    return@launch
                }
            }
        }
    }

    private suspend fun handleSocketEvent(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "force_logout" -> logout("Logged out on another device")
            "history_deleted" -> {
                conversationCaches.clear()
                uiState = uiState.copy(
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap()
                )
                refreshConversationSummaries()
            }
            "conversation_history_deleted" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                if (conversationId > 0L) conversationCaches.remove(conversationId)
                if (conversationId == uiState.currentConversationId) {
                    uiState = uiState.copy(
                        messages = emptyList(),
                        currentConversationDeliveryStates = emptyMap(),
                        currentConversationReadStates = emptyMap()
                    )
                    refreshCurrentConversationManage()
                }
                refreshConversationSummaries()
            }
            "attachments_cleared" -> {
                if (uiState.currentConversationId > 0L) {
                    conversationCaches.remove(uiState.currentConversationId)
                    openConversation(uiState.currentConversationId, forceRefresh = true)
                } else {
                    refreshConversationSummaries()
                }
            }
            "expired_cleanup" -> {
                val filteredMessages = uiState.messages.filter {
                    it.id < 0L || it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis()
                }
                cacheCurrentConversation(messages = filteredMessages)
                uiState = uiState.copy(messages = filteredMessages)
            }
            "user_updated" -> {
                refreshUsersAndMe()
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }
            "user_deleted", "conversation_members_changed" -> {
                refreshUsersAndMe()
                refreshConversationSummaries()
                if (uiState.currentConversationId > 0L && uiState.conversations.find { it.id == uiState.currentConversationId }?.kind == "group") {
                    runCatching { ensureGroupSenderKeys(uiState.currentConversationId, uploadOwnKey = false) }
                }
                refreshCurrentConversationManage()
            }
            "conversation_updated" -> {
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }
            "devices_changed" -> {
                refreshDeviceSecurityState()
            }
            "group_key_epoch_rotated" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                if (conversationId > 0L) {
                    if (conversationId == uiState.currentConversationId) {
                        runCatching { ensureGroupSenderKeys(conversationId, uploadOwnKey = true) }
                        refreshCurrentConversationManage()
                    }
                    refreshConversationSummaries()
                }
            }
            "conversation_removed" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                if (conversationId > 0L) conversationCaches.remove(conversationId)
                val remaining = uiState.conversations.filter { it.id != conversationId }
                uiState = uiState.copy(
                    conversations = remaining,
                    currentConversationId = if (uiState.currentConversationId == conversationId) remaining.firstOrNull()?.id ?: 0L else uiState.currentConversationId,
                    messages = if (uiState.currentConversationId == conversationId) emptyList() else uiState.messages,
                    currentConversationDeliveryStates = if (uiState.currentConversationId == conversationId) emptyMap() else uiState.currentConversationDeliveryStates,
                    currentConversationReadStates = if (uiState.currentConversationId == conversationId) emptyMap() else uiState.currentConversationReadStates
                )
                refreshConversationSummaries()
            }
            "call_invite" -> {
                val conversationId = json.optLong("conversation_id", 0L)
                val peerUserCode = json.optString("user_code")
                val peerUsername = json.optString("username")
                val accepted = callManager.receiveIncomingInvite(
                    conversationId = conversationId,
                    peerUserCode = peerUserCode,
                    peerUsername = peerUsername
                )
                if (accepted) {
                    prefs.setPendingIncomingCall(
                        PendingIncomingCallInvite(
                            conversationId = conversationId,
                            peerUserCode = peerUserCode,
                            peerUsername = peerUsername,
                            createdAt = json.optLong("ts", System.currentTimeMillis()),
                        )
                    )
                }
            }
            "call_accept" -> callManager.onRemoteAccepted()
            "call_reject" -> callManager.onRemoteRejected()
            "call_busy" -> callManager.onRemoteBusy()
            "call_unavailable" -> callManager.onRemoteUnavailable()
            "call_hangup" -> callManager.onRemoteHangup()
            "call_offer" -> {
                json.optJSONObject("description")?.let(callManager::onRemoteOffer)
            }
            "call_answer" -> {
                json.optJSONObject("description")?.let(callManager::onRemoteAnswer)
            }
            "call_ice" -> {
                json.optJSONObject("candidate")?.let(callManager::onRemoteIceCandidate)
            }
            "delivered_receipt" -> {
                refreshConversationSummaries()
                val conversationId = json.optLong("conversation_id", uiState.currentConversationId)
                  val username = json.optString("username")
                  val userCode = json.optString("user_code")
                  val lastDeliveredMessageId = json.optLong("last_delivered_message_id", 0L)
                  val key = identityKey(userCode, username)
                  if (conversationId == uiState.currentConversationId && key.isNotBlank()) {
                      val updatedStates = uiState.currentConversationDeliveryStates.toMutableMap().apply {
                          this[key] = maxOf(this[key] ?: 0L, lastDeliveredMessageId)
                      }
                      cacheCurrentConversation(deliveryStates = updatedStates)
                      uiState = uiState.copy(currentConversationDeliveryStates = updatedStates)
                }
                refreshCurrentDeliveryStates()
            }
            "read_receipt" -> {
                refreshConversationSummaries()
                val conversationId = json.optLong("conversation_id", uiState.currentConversationId)
                  val username = json.optString("username")
                  val userCode = json.optString("user_code")
                  val lastReadMessageId = json.optLong("last_read_message_id", 0L)
                  val key = identityKey(userCode, username)
                  if (conversationId == uiState.currentConversationId && key.isNotBlank()) {
                      val updatedStates = uiState.currentConversationReadStates.toMutableMap().apply {
                          this[key] = maxOf(this[key] ?: 0L, lastReadMessageId)
                      }
                      cacheCurrentConversation(readStates = updatedStates)
                      uiState = uiState.copy(currentConversationReadStates = updatedStates)
                }
                refreshCurrentReadStates()
            }
            "message_recalled" -> {
                val updated = ChatApi.parseMessage(json.getJSONObject("message"))
                if (updated.conversationId == uiState.currentConversationId) {
                    val updatedMessages = uiState.messages.map { if (it.id == updated.id) updated else it }
                    cacheCurrentConversation(messages = updatedMessages)
                    uiState = uiState.copy(messages = updatedMessages)
                }
                refreshConversationSummaries()
            }
            "chat" -> {
                val message = ChatApi.parseMessage(json)
                markDelivered(message.conversationId, message.id)
                if (
                    message.e2ee &&
                    GroupSenderKeyCrypto.isGroupPayload(message.payload) &&
                    uiState.conversations.find { it.id == message.conversationId }?.kind == "group"
                ) {
                    runCatching { ensureGroupSenderKeys(message.conversationId, uploadOwnKey = false) }
                }
                if (message.conversationId == uiState.currentConversationId && uiState.messages.none { it.id == message.id && it.id > 0L }) {
                    val mergedMessages = mergeMessages(uiState.messages, listOf(message))
                    cacheCurrentConversation(messages = mergedMessages)
                    uiState = uiState.copy(messages = mergedMessages)
                    markReadLatest()
                }
                refreshConversationSummaries()
            }
        }
    }

    private fun refreshUsersAndMe() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val meDeferred = async { api.me(uiState.serverUrl, prefs.token) }
                        val usersDeferred = async { api.users(uiState.serverUrl, prefs.token) }
                        Pair(meDeferred.await(), usersDeferred.await())
                    }
                }
            }.onSuccess { snapshot ->
                uiState = uiState.copy(me = snapshot.first, users = snapshot.second)
            }
        }
    }

    private fun refreshDeviceSecurityState() {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.deviceIdentities(uiState.serverUrl, prefs.token) to api.myDevices(uiState.serverUrl, prefs.token)
                }
            }.onSuccess { (identities, myDevices) ->
                uiState = uiState.copy(deviceIdentities = identities, myDevices = myDevices)
            }
        }
    }

    fun refreshMyDevices() {
        refreshDeviceSecurityState()
    }

    fun removeMyDevice(deviceId: String) {
        if (prefs.token.isBlank() || deviceId.isBlank() || deviceId == prefs.deviceId) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.deleteDevice(uiState.serverUrl, prefs.token, deviceId, prefs.deviceId)
                }
            }.onSuccess {
                refreshDeviceSecurityState()
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(error = error.message ?: "Unable to remove device")
            }
        }
    }

    private fun refreshConversationSummaries() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.conversations(uiState.serverUrl, prefs.token) }
            }.onSuccess { conversations ->
                uiState = uiState.copy(conversations = conversations)
                primeConversationCaches(conversations, excludingConversationId = uiState.currentConversationId)
            }
        }
    }

    private fun relationshipErrorMessage(error: Throwable): String {
        val strings = stringsFor(uiState.language)
        return when (error.message.orEmpty()) {
            "user_not_found" -> strings.userNotFound
            "group_not_found" -> strings.groupNotFound
            "bad_user_code" -> strings.invalidUserId
            "bad_group_code" -> strings.invalidGroupId
            "bad_group_title" -> strings.groupNameRequired
            "incoming_request_pending" -> strings.incomingContactAlreadyPending
            "already_in_group", "already_member" -> strings.alreadyInGroup
            "not_contact" -> strings.notContact
            "not_group_admin" -> strings.notGroupAdmin
            "owner_required" -> strings.requestFailed
            "owner_cannot_leave_group" -> strings.ownerCannotLeaveGroup
            "cannot_transfer_to_self" -> strings.requestFailed
            else -> strings.requestFailed
        }
    }

    private fun refreshRelationshipState() {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val contactsDeferred = async { api.contactRequests(uiState.serverUrl, token) }
                        val groupsDeferred = async { api.groupJoinRequests(uiState.serverUrl, token) }
                        Pair(contactsDeferred.await(), groupsDeferred.await())
                    }
                }
            }.onSuccess { result ->
                uiState = uiState.copy(contactRequests = result.first, groupJoinRequests = result.second)
            }
        }
    }

    fun clearRelationshipMessage() {
        uiState = uiState.copy(relationshipMessage = null, userLookupResult = null, groupLookupResult = null)
    }

    fun lookupUserByCode(userCode: String) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null, userLookupResult = null, error = null)
            runCatching {
                withContext(Dispatchers.IO) { api.lookupUser(uiState.serverUrl, token, userCode) }
            }.onSuccess { result ->
                uiState = uiState.copy(userLookupResult = result, isRefreshing = false)
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun requestContact(userCode: String) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.requestContact(uiState.serverUrl, token, userCode) }
            }.onSuccess { conversationId ->
                uiState = uiState.copy(
                    relationshipMessage = if (conversationId > 0L) stringsFor(uiState.language).alreadyConnected else stringsFor(uiState.language).contactRequestSent,
                    isRefreshing = false
                )
                refreshRelationshipState()
                refreshConversationSummaries()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun reviewContactRequest(requestId: Long, approve: Boolean) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.reviewContactRequest(uiState.serverUrl, token, requestId, approve) }
            }.onSuccess {
                uiState = uiState.copy(
                    relationshipMessage = if (approve) stringsFor(uiState.language).contactApproved else stringsFor(uiState.language).requestRejected,
                    isRefreshing = false
                )
                refreshRelationshipState()
                refreshConversationSummaries()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun createGroup(title: String) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.createGroup(uiState.serverUrl, token, title) }
            }.onSuccess {
                uiState = uiState.copy(relationshipMessage = stringsFor(uiState.language).groupCreated, isRefreshing = false)
                refreshConversationSummaries()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun lookupGroupByCode(groupCode: String) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null, groupLookupResult = null, error = null)
            runCatching {
                withContext(Dispatchers.IO) { api.lookupGroup(uiState.serverUrl, token, groupCode) }
            }.onSuccess { result ->
                uiState = uiState.copy(groupLookupResult = result, isRefreshing = false)
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun requestJoinGroup(groupCode: String) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.requestJoinGroup(uiState.serverUrl, token, groupCode) }
            }.onSuccess { conversationId ->
                uiState = uiState.copy(
                    relationshipMessage = if (conversationId > 0L) stringsFor(uiState.language).alreadyInGroup else stringsFor(uiState.language).joinRequestSent,
                    isRefreshing = false
                )
                refreshRelationshipState()
                refreshConversationSummaries()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    fun reviewGroupJoinRequest(requestId: Long, approve: Boolean) {
        val token = prefs.token
        if (token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, relationshipMessage = null)
            runCatching {
                withContext(Dispatchers.IO) { api.reviewGroupJoinRequest(uiState.serverUrl, token, requestId, approve) }
            }.onSuccess {
                uiState = uiState.copy(
                    relationshipMessage = if (approve) stringsFor(uiState.language).joinApproved else stringsFor(uiState.language).requestRejected,
                    isRefreshing = false
                )
                refreshRelationshipState()
                refreshConversationSummaries()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error), isRefreshing = false)
            }
        }
    }

    private fun refreshCurrentReadStates() {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.conversationReadStates(uiState.serverUrl, prefs.token, conversationId)
                        .associate { identityKey(it.userCode, it.username) to it.lastReadMessageId }
                }
            }.onSuccess { readStates ->
                cacheCurrentConversation(readStates = readStates)
                uiState = uiState.copy(currentConversationReadStates = readStates)
            }
        }
    }

    private fun refreshCurrentDeliveryStates() {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.conversationDeliveryStates(uiState.serverUrl, prefs.token, conversationId)
                        .associate { identityKey(it.userCode, it.username) to it.lastDeliveredMessageId }
                }
            }.onSuccess { deliveryStates ->
                cacheCurrentConversation(deliveryStates = deliveryStates)
                uiState = uiState.copy(currentConversationDeliveryStates = deliveryStates)
            }
        }
    }

    fun refreshCurrentConversationManage() {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.conversationManage(uiState.serverUrl, prefs.token, conversationId) }
            }.onSuccess { manage ->
                uiState = uiState.copy(currentConversationManage = manage)
            }
        }
    }

    fun clearCurrentConversationHistory() {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) { api.clearConversationHistory(uiState.serverUrl, prefs.token, conversationId) }
            }.onSuccess {
                conversationCaches.remove(conversationId)
                uiState = uiState.copy(
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap(),
                    isRefreshing = false
                )
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(isRefreshing = false, relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun changeCurrentGroupTitle(title: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.changeGroupTitle(uiState.serverUrl, prefs.token, conversationId, title) }
            }.onSuccess {
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun setCurrentGroupExpiration(ttlMs: Long) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.setGroupExpiration(uiState.serverUrl, prefs.token, conversationId, ttlMs) }
            }.onSuccess {
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun uploadCurrentGroupAvatar(fileName: String, mime: String, bytes: ByteArray) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isLoading = true,
                uploadProgress = TransferProgress(fileName, 0f, 0L, bytes.size.toLong()),
                error = null
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadConversationAvatar(uiState.serverUrl, prefs.token, conversationId, fileName, mime, bytes) { done, total ->
                        uiState = uiState.copy(
                            uploadProgress = TransferProgress(fileName, progressOf(done, total), done, total)
                        )
                    }
                }
            }.onSuccess {
                uiState = uiState.copy(isLoading = false, uploadProgress = null)
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }.onFailure {
                uiState = uiState.copy(
                    isLoading = false,
                    uploadProgress = null,
                    relationshipMessage = it.message ?: "Group avatar upload failed"
                )
            }
        }
    }

    fun removeCurrentGroupMember(userCode: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.removeGroupMember(uiState.serverUrl, prefs.token, conversationId, userCode) }
            }.onSuccess {
                refreshUsersAndMe()
                refreshConversationSummaries()
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun addCurrentGroupMember(userCode: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.addGroupMember(uiState.serverUrl, prefs.token, conversationId, userCode) }
            }.onSuccess {
                refreshUsersAndMe()
                refreshConversationSummaries()
                refreshCurrentConversationManage()
                uiState = uiState.copy(relationshipMessage = stringsFor(uiState.language).memberAdded)
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun transferCurrentGroupOwner(userCode: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.transferGroupOwner(uiState.serverUrl, prefs.token, conversationId, userCode) }
            }.onSuccess {
                refreshConversationSummaries()
                refreshCurrentConversationManage()
                uiState = uiState.copy(relationshipMessage = stringsFor(uiState.language).ownerTransferred)
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun requestCurrentGroupAdmin(userCode: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.requestGroupAdmin(uiState.serverUrl, prefs.token, conversationId, userCode) }
            }.onSuccess {
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun removeCurrentGroupAdmin(userCode: String) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L || prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.removeGroupAdmin(uiState.serverUrl, prefs.token, conversationId, userCode) }
            }.onSuccess {
                refreshConversationSummaries()
                refreshCurrentConversationManage()
                uiState = uiState.copy(relationshipMessage = stringsFor(uiState.language).adminRemoved)
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    fun reviewGroupAdminRequest(requestId: Long, approve: Boolean) {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.reviewGroupAdminRequest(uiState.serverUrl, prefs.token, requestId, approve) }
            }.onSuccess {
                refreshCurrentConversationManage()
            }.onFailure { error ->
                uiState = uiState.copy(relationshipMessage = relationshipErrorMessage(error))
            }
        }
    }

    private suspend fun sendTextInternal(
        text: String,
        reply: ReplyPreview?,
        existingMessageId: Long? = null,
    ) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L) return
        val me = uiState.me ?: return
        val conversation = uiState.conversations.find { it.id == conversationId }
        val preferDirectRatchet = uiState.e2eeEnabled && conversation?.kind == "direct"
        val preferGroupSenderKey = uiState.e2eeEnabled && conversation?.kind == "group"
        if (uiState.e2eeEnabled && !preferDirectRatchet && !preferGroupSenderKey) {
            uiState = uiState.copy(error = "Encryption key unavailable")
            return
        }

        val mentions = extractMentions(text)
        val payloadText = if (uiState.e2eeEnabled) {
            val directPayload = if (preferDirectRatchet) {
                runCatching {
                    val recipient = withContext(Dispatchers.IO) {
                        DirectMessageCrypto.cachedRecipientBundle(prefs, conversationId)
                            ?: api.directPreKeys(uiState.serverUrl, prefs.token, conversationId).firstOrNull()
                                ?.also { DirectMessageCrypto.rememberRecipientBundle(prefs, conversationId, it) }
                            ?: throw IllegalStateException("peer_prekey_unavailable")
                    }
                    withContext(Dispatchers.Default) {
                        DirectMessageCrypto.encryptText(prefs, conversationId, recipient, text)
                    }
                }.getOrNull()
            } else {
                null
            }
            val groupPayload = if (preferGroupSenderKey) {
                runCatching {
                    val epoch = ensureGroupSenderKeys(conversationId)
                    withContext(Dispatchers.Default) {
                        GroupSenderKeyCrypto.encryptText(
                            prefs,
                            conversationId,
                            me.userCode.ifBlank { me.username },
                            me.username,
                            epoch,
                            text
                        )
                    }
                }.getOrNull()
            } else {
                null
            }
            directPayload ?: groupPayload ?: run {
                uiState = uiState.copy(error = "Peer encryption key unavailable")
                return
            }
        } else {
            text
        }
        val messageId = existingMessageId ?: nextLocalMessageId--
        val optimisticMessage = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            ts = pendingOutgoing[messageId]?.message?.ts ?: System.currentTimeMillis(),
            expiresAt = 0L,
            userCode = me.userCode,
            username = me.username,
            color = me.color,
            kind = "text",
            payload = payloadText,
            e2ee = uiState.e2eeEnabled,
            replyTo = reply,
            mentions = mentions,
            localSendState = LocalSendState.SENDING
        )
        upsertPendingMessage(
            optimisticMessage,
            PendingAction.Text(text = text, reply = reply),
            scheduleTimeout = false
        )

        val success = socket?.takeIf { uiState.isConnected }?.send(
            JSONObject()
                .put("type", "chat")
                .put("conversation_id", conversationId)
                .put("kind", "text")
                .put("mentions", JSONArray(mentions))
                .put("reply_to", reply?.toJson().orEmpty())
                .put("e2ee", if (uiState.e2eeEnabled) 1 else 0)
                .put("payload", payloadText)
                .toString()
        ) == true

        if (success) {
            schedulePendingFailure(messageId)
        } else {
            markPendingMessageFailed(messageId)
        }
    }

    private suspend fun uploadAttachmentInternal(
        kind: String,
        fileName: String,
        mime: String,
        localFile: File,
        reply: ReplyPreview?,
        durationMs: Long = 0L,
        existingMessageId: Long? = null,
    ) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L) return
        val me = uiState.me ?: return
        val conversation = uiState.conversations.find { it.id == conversationId }
        if (uiState.e2eeEnabled && conversation?.kind !in setOf("direct", "group")) {
            uiState = uiState.copy(error = "Encryption key unavailable")
            return
        }

        val plainSize = withContext(Dispatchers.IO) { localFile.length() }
        val directRecipient = if (conversation?.kind == "direct") {
            withContext(Dispatchers.IO) {
                DirectMessageCrypto.cachedRecipientBundle(prefs, conversationId)
                    ?: api.directPreKeys(uiState.serverUrl, prefs.token, conversationId).firstOrNull()
                        ?.also { DirectMessageCrypto.rememberRecipientBundle(prefs, conversationId, it) }
            }
        } else {
            null
        }
        val groupEpoch = if (conversation?.kind == "group") ensureGroupSenderKeys(conversationId) else 0L
        val payload = withContext(Dispatchers.IO) {
            val encryptedFile = File.createTempFile("pending_upload_enc_", ".enc", app.cacheDir)
            val fileKey = ChatCrypto.randomFileKey()
            val encrypted = ChatCrypto.encryptBinaryFileWithKey(
                fileKey = fileKey,
                sourceFile = localFile,
                targetFile = encryptedFile,
                aad = ChatCrypto.attachmentFileAad(conversationId, kind)
            )
            val meta = JSONObject()
                .put("name", fileName)
                .put("mime", mime)
                .put("size", plainSize)
                .put("durationMs", durationMs)
            val keyPlain = JSONObject().put("file_key", fileKey).toString()
            val keyWrap = when {
                directRecipient != null -> JSONObject()
                    .put("scheme", "direct-dr")
                    .put(
                        "payload",
                        DirectMessageCrypto.encryptText(prefs, conversationId, directRecipient, keyPlain)
                    )
                conversation?.kind == "group" -> JSONObject()
                    .put("scheme", "group-sender")
                    .put(
                        "payload",
                        GroupSenderKeyCrypto.encryptText(
                            prefs,
                            conversationId,
                            me.userCode.ifBlank { me.username },
                            me.username,
                            groupEpoch,
                            keyPlain
                        )
                    )
                else -> throw IllegalStateException("Peer encryption key unavailable")
            }
            val payloadJson = JSONObject()
                .put("v", 3)
                .put(
                    "enc",
                    JSONObject()
                        .put("v", 3)
                        .put("kdf", "HKDF-SHA256")
                        .put("aead", "AES-256-GCM")
                        .put("stream", "file-key-gcm")
                        .put("iv", encrypted.iv)
                )
                .put("key_wrap", keyWrap)
                .put(
                    "meta",
                    ChatCrypto.encryptTextWithKey(
                        fileKey,
                        meta.toString(),
                        ChatCrypto.attachmentMetaAad(conversationId, kind)
                    )
                )
                .toString()
            Triple(payloadJson, encryptedFile, plainSize)
        }

        val messageId = existingMessageId ?: nextLocalMessageId--
        val optimisticMessage = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            ts = pendingOutgoing[messageId]?.message?.ts ?: System.currentTimeMillis(),
            expiresAt = 0L,
            userCode = me.userCode,
            username = me.username,
            color = me.color,
            kind = kind,
            payload = payload.first,
            e2ee = true,
            replyTo = reply,
            mentions = emptyList(),
            localSendState = LocalSendState.SENDING,
            localAttachmentPath = localFile.absolutePath,
            localAttachmentName = fileName,
            localAttachmentMime = mime,
            localAttachmentSize = plainSize,
            localAttachmentDurationMs = durationMs
        )
        upsertPendingMessage(
            optimisticMessage,
            PendingAction.Attachment(
                kind = kind,
                fileName = fileName,
                mime = mime,
                localFilePath = localFile.absolutePath,
                reply = reply,
                durationMs = durationMs
            ),
            scheduleTimeout = false
        )

        uiState = uiState.copy(
            isLoading = true,
            uploadProgress = TransferProgress(fileName, 0f, 0L, plainSize),
            error = null
        )
        val uploadResult = try {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadAttachment(
                        uiState.serverUrl,
                        prefs.token,
                        conversationId = conversationId,
                        kind = kind,
                        payloadJson = payload.first,
                        replyJson = reply?.toJson().orEmpty(),
                        encryptedFile = payload.second,
                    ) { done, total ->
                        uiState = uiState.copy(uploadProgress = TransferProgress(fileName, progressOf(done, total), done, total))
                    }
                }
            }
        } finally {
            payload.second.delete()
        }
        uploadResult
        .onSuccess { confirmedMessage ->
            uiState = uiState.copy(isLoading = false, uploadProgress = null)
            val existingMessages =
                if (conversationId == uiState.currentConversationId) uiState.messages
                else conversationCaches[conversationId]?.messages.orEmpty()
            val mergedMessages = mergeMessages(existingMessages, listOf(confirmedMessage))
            val cache = conversationCaches[conversationId] ?: ConversationCache()
            conversationCaches[conversationId] = cache.copy(messages = mergedMessages)
            if (conversationId == uiState.currentConversationId) uiState = uiState.copy(messages = mergedMessages)
            refreshConversationSummaries()
        }.onFailure {
            uiState = uiState.copy(isLoading = false, uploadProgress = null)
            markPendingMessageFailed(messageId)
        }
    }

    fun sendText(text: String, reply: ReplyPreview?) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            sendTextInternal(trimmed, reply)
        }
    }

    fun uploadAttachment(kind: String, fileName: String, mime: String, bytes: ByteArray, reply: ReplyPreview?, durationMs: Long = 0L) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0) return
        viewModelScope.launch {
            val localFile = withContext(Dispatchers.IO) {
                val suffix = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
                File.createTempFile("pending_upload_", suffix, app.cacheDir).apply { writeBytes(bytes) }
            }
            uploadAttachmentInternal(
                kind = kind,
                fileName = fileName,
                mime = mime,
                localFile = localFile,
                reply = reply,
                durationMs = durationMs
            )
        }
    }

    fun uploadAttachmentFromUri(kind: String, fileName: String, mime: String, uri: Uri, reply: ReplyPreview?, durationMs: Long = 0L) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0) return
        viewModelScope.launch {
            val localFile = withContext(Dispatchers.IO) {
                val suffix = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".bin"
                val temp = File.createTempFile("pending_upload_", suffix, app.cacheDir)
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    } ?: throw IOException("Unable to open attachment")
                    temp
                } catch (error: Throwable) {
                    temp.delete()
                    throw error
                }
            }
            uploadAttachmentInternal(
                kind = kind,
                fileName = fileName,
                mime = mime,
                localFile = localFile,
                reply = reply,
                durationMs = durationMs
            )
        }
    }

    fun uploadAvatar(fileName: String, mime: String, bytes: ByteArray) {
        viewModelScope.launch {
            uiState = uiState.copy(isLoading = true, uploadProgress = TransferProgress(fileName, 0f, 0L, bytes.size.toLong()), error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    api.uploadAvatar(uiState.serverUrl, prefs.token, fileName, mime, bytes) { done, total ->
                        uiState = uiState.copy(uploadProgress = TransferProgress(fileName, progressOf(done, total), done, total))
                    }
                }
            }.onSuccess {
                uiState = uiState.copy(isLoading = false, uploadProgress = null)
                refreshUsersAndMe()
                refreshConversationSummaries()
            }.onFailure {
                uiState = uiState.copy(isLoading = false, uploadProgress = null, error = it.message ?: "Avatar upload failed")
            }
        }
    }

    fun retryMessage(messageId: Long) {
        val pending = pendingOutgoing[messageId] ?: return
        viewModelScope.launch {
            when (val action = pending.action) {
                is PendingAction.Text -> sendTextInternal(action.text, action.reply, existingMessageId = messageId)
                is PendingAction.Attachment -> {
                    val localFile = File(action.localFilePath)
                    if (!localFile.exists()) {
                        markPendingMessageFailed(messageId)
                        uiState = uiState.copy(error = "Attachment not available")
                        return@launch
                    }
                    uploadAttachmentInternal(
                        kind = action.kind,
                        fileName = action.fileName,
                        mime = action.mime,
                        localFile = localFile,
                        reply = action.reply,
                        durationMs = action.durationMs,
                        existingMessageId = messageId
                    )
                }
            }
        }
    }

    fun changePassword(newPassword: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.changePassword(uiState.serverUrl, prefs.token, newPassword) }
            }.onSuccess {
                val loginName = (uiState.me?.username ?: prefs.savedLoginUsername.ifBlank { prefs.username }).trim()
                if (loginName.isNotBlank()) {
                    prefs.saveLogin(loginName, newPassword)
                }
                logout("Password changed. Please log in again.")
            }.onFailure {
                uiState = uiState.copy(error = it.message ?: "Unable to change password")
            }
        }
    }

    fun checkForUpdates() {
        checkReleaseChannel(AppReleaseChannel.STABLE)
    }

    fun checkForPrerelease() {
        checkReleaseChannel(AppReleaseChannel.PRERELEASE)
    }

    private fun checkReleaseChannel(channel: AppReleaseChannel) {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            val strings = stringsFor(uiState.language)
            uiState = when (channel) {
                AppReleaseChannel.STABLE -> uiState.copy(isCheckingUpdate = true, updateStatus = strings.checkingUpdate, error = null)
                AppReleaseChannel.PRERELEASE -> uiState.copy(isCheckingPrerelease = true, prereleaseStatus = strings.checkingUpdate, error = null)
            }
            runCatching {
                withContext(Dispatchers.IO) { api.appRelease(uiState.serverUrl, prefs.token, channel) }
            }.onSuccess { release ->
                val downloadedUpdate = release?.takeIf { it.version.isNotBlank() }?.let {
                    resolveDownloadedRelease(it.channel, it.version, releaseFileNameFor(it))
                }
                val status = when (channel) {
                    AppReleaseChannel.STABLE -> when {
                        release == null || release.version.isBlank() || release.downloadUrl.isBlank() -> strings.noReleaseUploaded
                        compareVersionNames(release.version, currentAppVersion()) > 0 -> "${strings.updateAvailable}: ${release.versionLabel.ifBlank { release.version }}"
                        else -> strings.latestVersionInstalled
                    }
                    AppReleaseChannel.PRERELEASE -> when {
                        release == null || release.version.isBlank() || release.downloadUrl.isBlank() -> strings.noPrereleaseUploaded
                        else -> "${strings.prereleaseAvailable}: ${release.versionLabel.ifBlank { release.version }}"
                    }
                }
                uiState = when (channel) {
                    AppReleaseChannel.STABLE -> uiState.copy(
                        latestAppRelease = release,
                        downloadedUpdate = downloadedUpdate,
                        isCheckingUpdate = false,
                        updateStatus = status
                    )
                    AppReleaseChannel.PRERELEASE -> uiState.copy(
                        latestPrerelease = release,
                        downloadedPrerelease = downloadedUpdate,
                        isCheckingPrerelease = false,
                        prereleaseStatus = status
                    )
                }
            }.onFailure {
                val shouldSuppressTransientError = it.isTransientNetworkIssue()
                uiState = when (channel) {
                    AppReleaseChannel.STABLE -> uiState.copy(
                        isCheckingUpdate = false,
                        updateStatus = if (shouldSuppressTransientError) uiState.updateStatus else null,
                        error = if (shouldSuppressTransientError) null else (it.message ?: "Unable to check update")
                    )
                    AppReleaseChannel.PRERELEASE -> uiState.copy(
                        isCheckingPrerelease = false,
                        prereleaseStatus = if (shouldSuppressTransientError) uiState.prereleaseStatus else null,
                        error = if (shouldSuppressTransientError) null else (it.message ?: "Unable to check prerelease")
                    )
                }
            }
        }
    }

    fun onAppForeground() {
        isAppInForeground = true
        if (prefs.token.isBlank()) return
        if (!uiState.isLoggedIn) {
            if (!uiState.isLoading) restoreSession()
            return
        }
        FcmRegistrar(app).registerCurrentTokenIfAvailable()
        syncSocketPresence()
        restoreVoiceCallStateFromRuntime()
        restorePendingIncomingCallIfNeeded()
        val now = System.currentTimeMillis()
        if (now - lastForegroundRecoveryAt < 3_000L) return
        lastForegroundRecoveryAt = now

        val socketLooksStale = lastSocketActivityAt > 0L && now - lastSocketActivityAt > 45_000L
        when {
            socket == null || !uiState.isConnected -> refreshNow(reconnectIfNeeded = true, showIndicator = false)
            socketLooksStale -> {
                uiState = uiState.copy(isConnected = false)
                reconnectSocketNow()
            }
            else -> refreshNow(reconnectIfNeeded = false, showIndicator = false)
        }
    }

    fun onAppBackground() {
        isAppInForeground = false
        syncSocketPresence()
    }

    fun recallMessage(message: ChatMessage) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.recallMessage(uiState.serverUrl, prefs.token, message.id) }
            }.onFailure {
                uiState = uiState.copy(error = it.message ?: "Unable to recall message")
            }
        }
    }

    fun deleteHistory() {
        if (uiState.me?.isAdmin != true) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.deleteHistory(uiState.serverUrl, prefs.token) }
            }.onFailure {
                uiState = uiState.copy(error = it.message ?: "Unable to delete history")
            }
        }
    }

    fun deleteCurrentDirectConversation() {
        val conversation = uiState.conversations.find { it.id == uiState.currentConversationId }
        if (conversation?.kind != "direct") return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    api.deleteDirectConversation(uiState.serverUrl, prefs.token, conversation.id)
                }
            }.onSuccess {
                conversationCaches.remove(conversation.id)
                val conversations = withContext(Dispatchers.IO) { api.conversations(uiState.serverUrl, prefs.token) }
                uiState = uiState.copy(
                    conversations = conversations,
                    currentConversationId = conversations.firstOrNull()?.id ?: 0L,
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap(),
                    isRefreshing = false,
                    relationshipMessage = stringsFor(uiState.language).conversationDeleted
                )
            }.onFailure {
                uiState = uiState.copy(
                    isRefreshing = false,
                    error = stringsFor(uiState.language).unableToDeleteConversation
                )
            }
        }
    }

    fun deleteCurrentGroupConversation() {
        val conversation = uiState.conversations.find { it.id == uiState.currentConversationId }
        if (conversation?.kind != "group") return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    api.deleteGroupConversation(uiState.serverUrl, prefs.token, conversation.id)
                }
            }.onSuccess {
                conversationCaches.remove(conversation.id)
                val conversations = withContext(Dispatchers.IO) { api.conversations(uiState.serverUrl, prefs.token) }
                uiState = uiState.copy(
                    conversations = conversations,
                    currentConversationId = conversations.firstOrNull()?.id ?: 0L,
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap(),
                    isRefreshing = false,
                    relationshipMessage = stringsFor(uiState.language).groupDeleted
                )
            }.onFailure {
                uiState = uiState.copy(
                    isRefreshing = false,
                    error = stringsFor(uiState.language).unableToDeleteGroup
                )
            }
        }
    }

    fun leaveCurrentGroupConversation() {
        val conversation = uiState.conversations.find { it.id == uiState.currentConversationId }
        if (conversation?.kind != "group") return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    api.leaveGroupConversation(uiState.serverUrl, prefs.token, conversation.id)
                }
            }.onSuccess {
                conversationCaches.remove(conversation.id)
                val conversations = withContext(Dispatchers.IO) { api.conversations(uiState.serverUrl, prefs.token) }
                uiState = uiState.copy(
                    conversations = conversations,
                    currentConversationId = conversations.firstOrNull()?.id ?: 0L,
                    messages = emptyList(),
                    currentConversationDeliveryStates = emptyMap(),
                    currentConversationReadStates = emptyMap(),
                    isRefreshing = false,
                    relationshipMessage = stringsFor(uiState.language).groupLeft
                )
            }.onFailure {
                uiState = uiState.copy(
                    isRefreshing = false,
                    error = relationshipErrorMessage(it).takeIf { message -> message != stringsFor(uiState.language).requestFailed }
                        ?: stringsFor(uiState.language).unableToLeaveGroup
                )
            }
        }
    }

    fun requestAccountDeletion() {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            uiState = uiState.copy(isRefreshing = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) { api.requestAccountDeletion(uiState.serverUrl, prefs.token) }
            }.onSuccess {
                uiState = uiState.copy(
                    isRefreshing = false,
                    relationshipMessage = stringsFor(uiState.language).accountDeletionRequested
                )
            }.onFailure {
                uiState = uiState.copy(isRefreshing = false, error = it.message ?: stringsFor(uiState.language).requestFailed)
            }
        }
    }

    fun refreshCallConfig() {
        if (prefs.token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.callConfig(uiState.serverUrl, prefs.token) }
            }.onSuccess { config ->
                callManager.updateIceServers(config.iceServers)
            }
        }
    }

    fun startVoiceCall(conversationId: Long) {
        val conversation = uiState.conversations.find { it.id == conversationId }
        if (conversation?.kind != "direct") {
            uiState = uiState.copy(error = stringsFor(uiState.language).directCallOnly)
            return
        }
        val peer = uiState.users.find {
            (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) ||
                it.username == conversation.directUsername
        }
        if (peer == null) {
            uiState = uiState.copy(error = stringsFor(uiState.language).callUnavailable)
            return
        }
        if (socket == null || !uiState.isConnected) {
            uiState = uiState.copy(error = stringsFor(uiState.language).disconnected)
            return
        }
        callManager.startOutgoing(conversationId, peer.userCode, peer.username)
    }

    fun acceptIncomingCall() {
        callManager.restoreIncomingInvite(uiState.voiceCall.copy(statusMessage = "incoming"))
        if (!uiState.isConnected || socket == null) {
            pendingIncomingCallAction = "accept"
            reconnectSocketNow()
            return
        }
        callManager.acceptIncoming()
    }

    fun declineIncomingCall() {
        callManager.restoreIncomingInvite(uiState.voiceCall.copy(statusMessage = "incoming"))
        if (!uiState.isConnected || socket == null) {
            pendingIncomingCallAction = "reject"
            reconnectSocketNow()
            return
        }
        callManager.rejectIncoming()
    }

    fun endVoiceCall() {
        callManager.endCall()
    }

    fun toggleVoiceCallMute() {
        callManager.toggleMute()
    }

    fun toggleVoiceCallSpeaker() {
        callManager.toggleSpeaker()
    }

    fun setVoiceCallAudioRoute(route: VoiceAudioRoute) {
        callManager.selectAudioRoute(route)
    }

    fun syncVoiceCallAudioRouteFromSystem(route: VoiceAudioRoute) {
        callManager.syncSystemAudioRoute(route)
    }

    fun dismissVoiceCallStatus() {
        callManager.dismissTerminalState()
    }

    fun markReadLatest() {
        val ws = socket ?: return
        val latest = uiState.messages.lastOrNull { it.id > 0L && it.localSendState == LocalSendState.SENT }?.id ?: return
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0) return
        ws.send(
            JSONObject()
                .put("type", "read")
                .put("conversation_id", conversationId)
                .put("last_read_message_id", latest)
                .toString()
        )
    }

    private fun markDelivered(conversationId: Long, messageId: Long) {
        val ws = socket ?: return
        if (conversationId <= 0L || messageId <= 0L) return
        ws.send(
            JSONObject()
                .put("type", "delivered")
                .put("conversation_id", conversationId)
                .put("last_delivered_message_id", messageId)
                .toString()
        )
    }

    private fun markDeliveredLatest() {
        val latest = uiState.messages.lastOrNull { it.id > 0L && it.localSendState == LocalSendState.SENT }?.id ?: return
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L) return
        markDelivered(conversationId, latest)
    }

    private fun syncSocketPresence() {
        val ws = socket
        if (ws == null || !uiState.isConnected) {
            foregroundPresenceJob?.cancel()
            foregroundPresenceJob = null
            return
        }
        ws.send(
            JSONObject()
                .put("type", "presence")
                .put("state", if (isAppInForeground) "foreground" else "background")
                .toString()
        )
        if (!isAppInForeground) {
            foregroundPresenceJob?.cancel()
            foregroundPresenceJob = null
            return
        }
        if (foregroundPresenceJob?.isActive == true) return
        foregroundPresenceJob = viewModelScope.launch {
            while (socket === ws && uiState.isConnected && isAppInForeground) {
                delay(25_000L)
                if (socket !== ws || !uiState.isConnected || !isAppInForeground) break
                ws.send(
                    JSONObject()
                        .put("type", "presence")
                        .put("state", "foreground")
                        .toString()
                )
            }
        }
    }

    fun refreshNow(reconnectIfNeeded: Boolean = true, showIndicator: Boolean = true) {
        if (prefs.token.isBlank()) return
        if (refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            if (showIndicator) {
                uiState = uiState.copy(isRefreshing = true, error = null)
            }
            val currentConversationId = uiState.currentConversationId
            val latestMessageId = uiState.messages.lastOrNull()?.id ?: 0L
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val meDeferred = async { api.me(uiState.serverUrl, prefs.token) }
                        val usersDeferred = async { api.users(uiState.serverUrl, prefs.token) }
                        val conversationsDeferred = async { api.conversations(uiState.serverUrl, prefs.token) }
                        val deviceIdentitiesDeferred = async { api.deviceIdentities(uiState.serverUrl, prefs.token) }
                        val myDevicesDeferred = async { api.myDevices(uiState.serverUrl, prefs.token) }
                        val contactRequestsDeferred = async { api.contactRequests(uiState.serverUrl, prefs.token) }
                        val groupJoinRequestsDeferred = async { api.groupJoinRequests(uiState.serverUrl, prefs.token) }
                        val deliveryStatesDeferred = async {
                            if (currentConversationId <= 0L) emptyMap()
                            else {
                                runCatching {
                                    api.conversationDeliveryStates(uiState.serverUrl, prefs.token, currentConversationId)
                                        .associate { identityKey(it.userCode, it.username) to it.lastDeliveredMessageId }
                                }.getOrDefault(emptyMap())
                            }
                        }
                        val readStatesDeferred = async {
                            if (currentConversationId <= 0L) emptyMap()
                            else {
                                runCatching {
                                    api.conversationReadStates(uiState.serverUrl, prefs.token, currentConversationId)
                                        .associate { identityKey(it.userCode, it.username) to it.lastReadMessageId }
                                }.getOrDefault(emptyMap())
                            }
                        }
                        val messagesDeferred = async {
                            if (currentConversationId <= 0L) emptyList()
                            else if (latestMessageId > 0L) api.history(uiState.serverUrl, prefs.token, currentConversationId, sinceId = latestMessageId, limit = 200).items
                            else api.history(uiState.serverUrl, prefs.token, currentConversationId, limit = 200).items
                        }
                        RefreshSnapshot(
                            me = meDeferred.await(),
                            users = usersDeferred.await(),
                            conversations = conversationsDeferred.await(),
                            deviceIdentities = deviceIdentitiesDeferred.await(),
                            myDevices = myDevicesDeferred.await(),
                            contactRequests = contactRequestsDeferred.await(),
                            groupJoinRequests = groupJoinRequestsDeferred.await(),
                            deliveryStates = deliveryStatesDeferred.await(),
                            readStates = readStatesDeferred.await(),
                            messages = messagesDeferred.await(),
                        )
                    }
                }
            }.onSuccess { result ->
                val mergedMessages = if (currentConversationId <= 0L) uiState.messages else mergeMessages(uiState.messages, result.messages)
                if (currentConversationId > 0L && result.conversations.find { it.id == currentConversationId }?.kind == "group") {
                    runCatching { ensureGroupSenderKeys(currentConversationId, uploadOwnKey = false) }
                }
                if (currentConversationId > 0L) {
                    conversationCaches[currentConversationId] = ConversationCache(
                        messages = mergedMessages,
                        deliveryStates = result.deliveryStates,
                        readStates = result.readStates
                    )
                }
                uiState = uiState.copy(
                    me = result.me,
                    users = result.users,
                    conversations = result.conversations,
                    deviceIdentities = result.deviceIdentities,
                    myDevices = result.myDevices,
                    contactRequests = result.contactRequests,
                    groupJoinRequests = result.groupJoinRequests,
                    currentConversationDeliveryStates = result.deliveryStates,
                    currentConversationReadStates = result.readStates,
                    messages = mergedMessages,
                    isRefreshing = false,
                    error = null
                )
                if (reconnectIfNeeded && (socket == null || !uiState.isConnected)) {
                    connectSocket()
                } else {
                    markDeliveredLatest()
                    markReadLatest()
                }
            }.onFailure {
                if (it.isSessionAuthFailure()) {
                    uiState = uiState.copy(isRefreshing = false)
                    recoverExpiredSession("Session expired")
                } else {
                    uiState = uiState.copy(
                        isRefreshing = false,
                        error = if (showIndicator) (it.message ?: "Unable to refresh") else uiState.error
                    )
                    if (reconnectIfNeeded && (socket == null || !uiState.isConnected)) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    fun buildReplyPreview(message: ChatMessage): String = when (message.kind) {
        "image", "photo" -> "Image"
        "audio" -> "Voice note"
        "file" -> "File"
        "attachment_cleared" -> "Attachment removed"
        "recalled" -> "Message recalled"
        "text" -> {
            if (!message.e2ee) {
                message.payload.take(80)
            } else if (DirectMessageCrypto.isDirectPayload(message.payload)) {
                runCatching { DirectMessageCrypto.decryptText(prefs, message).take(80) }
                    .getOrElse { "Encrypted message" }
            } else if (GroupSenderKeyCrypto.isGroupPayload(message.payload)) {
                runCatching { GroupSenderKeyCrypto.decryptText(prefs, message.conversationId, message.payload).take(80) }
                    .getOrElse { "Encrypted message" }
            } else {
                "Encrypted message"
            }
        }
        else -> message.kind
    }

    suspend fun materializeAttachment(
        context: Context,
        message: ChatMessage,
        exportToDownloads: Boolean = true,
    ): DecryptedAttachment =
        try {
            withContext(Dispatchers.IO) {
                resolveAttachment(
                    context,
                    api,
                    uiState.serverUrl,
                    message,
                    prefs = prefs,
                    exportToDownloads = exportToDownloads,
                    onProgress = { done, total ->
                    uiState = uiState.copy(downloadProgress = TransferProgress(message.kind, progressOf(done, total), done, total))
                    }
                ).also {
                    uiState = uiState.copy(downloadProgress = null)
                }
            }
        } catch (t: Throwable) {
            uiState = uiState.copy(downloadProgress = null)
            throw t
        }

    suspend fun downloadLatestAppRelease(context: Context): DecryptedAttachment {
        return downloadRelease(context, AppReleaseChannel.STABLE)
    }

    suspend fun downloadLatestPrerelease(context: Context): DecryptedAttachment {
        return downloadRelease(context, AppReleaseChannel.PRERELEASE)
    }

    private suspend fun downloadRelease(context: Context, channel: AppReleaseChannel): DecryptedAttachment {
        val strings = stringsFor(uiState.language)
        val release = when (channel) {
            AppReleaseChannel.STABLE -> uiState.latestAppRelease
            AppReleaseChannel.PRERELEASE -> uiState.latestPrerelease
        } ?: throw IllegalStateException(if (channel == AppReleaseChannel.STABLE) strings.noReleaseUploaded else strings.noPrereleaseUploaded)
        if (release.downloadUrl.isBlank()) {
            throw IllegalStateException(if (channel == AppReleaseChannel.STABLE) strings.noReleaseUploaded else strings.noPrereleaseUploaded)
        }
        return try {
            withContext(Dispatchers.IO) {
                val fileName = releaseFileNameFor(release)
                val mime = "application/vnd.android.package-archive"
                val existingAttachment = resolveDownloadedRelease(channel, release.version, fileName)
                if (existingAttachment != null) {
                    uiState = when (channel) {
                        AppReleaseChannel.STABLE -> uiState.copy(
                            downloadProgress = null,
                            downloadedUpdate = existingAttachment,
                            updateStatus = strings.updateDownloaded
                        )
                        AppReleaseChannel.PRERELEASE -> uiState.copy(
                            downloadProgress = null,
                            downloadedPrerelease = existingAttachment,
                            prereleaseStatus = strings.updateDownloaded
                        )
                    }
                    return@withContext existingAttachment
                }
                val bytes = api.download(uiState.serverUrl, release.downloadUrl) { done, total ->
                    uiState = uiState.copy(
                        downloadProgress = TransferProgress(
                            release.fileName.ifBlank { release.originalName.ifBlank { "update.apk" } },
                            progressOf(done, total),
                            done,
                            total
                        )
                    )
                }
                val privateFile = appUpdateFile(context, fileName).apply { writeBytes(bytes) }
                val attachment = DecryptedAttachment(
                    name = fileName,
                    mime = mime,
                    size = bytes.size.toLong(),
                    file = privateFile
                )
                uiState = when (channel) {
                    AppReleaseChannel.STABLE -> uiState.copy(
                        downloadProgress = null,
                        downloadedUpdate = attachment,
                        updateStatus = strings.updateDownloaded
                    )
                    AppReleaseChannel.PRERELEASE -> uiState.copy(
                        downloadProgress = null,
                        downloadedPrerelease = attachment,
                        prereleaseStatus = strings.updateDownloaded
                    )
                }
                attachment
            }
        } catch (t: Throwable) {
            uiState = uiState.copy(downloadProgress = null)
            throw t
        }
    }

    fun clearDownloadedUpdate() {
        uiState = uiState.copy(downloadedUpdate = null, downloadedPrerelease = null)
    }

    fun clearDownloadProgress() {
        uiState = uiState.copy(downloadProgress = null)
    }

    fun logout(reason: String? = null, clearSavedLogin: Boolean = false) {
        val authToken = prefs.token
        val serverUrl = prefs.serverUrl
        allowReconnect = false
        sessionRecoveryJob?.cancel()
        sessionRecoveryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        refreshJob?.cancel()
        refreshJob = null
        clearVoiceCallJob?.cancel()
        clearVoiceCallJob = null
        pendingTimeoutJobs.values.forEach { it.cancel() }
        pendingTimeoutJobs.clear()
        pendingOutgoing.values.forEach { cleanupPendingAttachment(it.message) }
        pendingOutgoing.clear()
        reconnectAttempts = 0
        conversationCaches.clear()
        pendingIncomingCallAction = null
        callManager.release()
        VoiceCallRuntime.updateState(app, VoiceCallUiState())
        VoiceCallRuntime.detach()
        socket?.close(1000, null)
        socket = null
        if (authToken.isNotBlank() && serverUrl.isNotBlank()) {
            FcmRegistrar(app).unregisterCurrentTokenIfAvailable(authToken, serverUrl)
        }
        prefs.clearSession()
        prefs.setPendingIncomingCall(null)
        NotificationCenter.clearIncomingCallNotification(app)
        if (clearSavedLogin) {
            prefs.clearSavedLogin()
        }
        cancelNotifications()
        uiState = ChatUiState(
            serverUrl = prefs.serverUrl,
            e2eeEnabled = prefs.e2eeEnabled,
            language = AppLanguage.fromStored(prefs.language),
            displayMode = AppDisplayMode.fromStored(prefs.displayMode),
            dynamicColorsEnabled = prefs.dynamicColorsEnabled,
            error = reason
        )
    }

    private fun cacheCurrentConversation(
        messages: List<ChatMessage> = uiState.messages,
        deliveryStates: Map<String, Long> = uiState.currentConversationDeliveryStates,
        readStates: Map<String, Long> = uiState.currentConversationReadStates,
    ) {
        val conversationId = uiState.currentConversationId
        if (conversationId <= 0L) return
        conversationCaches[conversationId] = ConversationCache(
            messages = messages,
            deliveryStates = deliveryStates,
            readStates = readStates
        )
    }

    private fun pendingMessagesForConversation(conversationId: Long): List<ChatMessage> =
        pendingOutgoing.values
            .map { it.message }
            .filter { it.conversationId == conversationId }

    private fun upsertPendingMessage(
        message: ChatMessage,
        action: PendingAction,
        scheduleTimeout: Boolean,
    ) {
        pendingTimeoutJobs.remove(message.id)?.cancel()
        pendingOutgoing[message.id] = PendingOutgoing(action = action, message = message)

        val cache = conversationCaches[message.conversationId] ?: ConversationCache()
        val existingMessages = if (message.conversationId == uiState.currentConversationId) uiState.messages else cache.messages
        val updatedMessages = mergeMessages(existingMessages.filterNot { it.id == message.id }, listOf(message))
        conversationCaches[message.conversationId] = cache.copy(messages = updatedMessages)
        if (message.conversationId == uiState.currentConversationId) {
            uiState = uiState.copy(messages = updatedMessages)
        }
        if (scheduleTimeout) {
            schedulePendingFailure(message.id)
        }
    }

    private fun markPendingMessageFailed(messageId: Long) {
        pendingTimeoutJobs.remove(messageId)?.cancel()
        val pending = pendingOutgoing[messageId] ?: return
        val failed = pending.message.copy(localSendState = LocalSendState.FAILED)
        pendingOutgoing[messageId] = pending.copy(message = failed)

        val cache = conversationCaches[failed.conversationId] ?: ConversationCache()
        val existingMessages = if (failed.conversationId == uiState.currentConversationId) uiState.messages else cache.messages
        val updatedMessages = mergeMessages(existingMessages.filterNot { it.id == failed.id }, listOf(failed))
        conversationCaches[failed.conversationId] = cache.copy(messages = updatedMessages)
        if (failed.conversationId == uiState.currentConversationId) {
            uiState = uiState.copy(messages = updatedMessages)
        }
    }

    private fun schedulePendingFailure(messageId: Long, delayMs: Long = 8_000L) {
        pendingTimeoutJobs.remove(messageId)?.cancel()
        pendingTimeoutJobs[messageId] = viewModelScope.launch {
            delay(delayMs)
            markPendingMessageFailed(messageId)
        }
    }

    private fun resolveMatchingPendingMessageId(message: ChatMessage): Long? {
        if (message.id <= 0L) return null
        val incomingSender = identityKey(message.userCode, message.username)
        return pendingOutgoing.values.firstOrNull { pending ->
            val local = pending.message
            local.conversationId == message.conversationId &&
                identityKey(local.userCode, local.username) == incomingSender &&
                local.kind == message.kind &&
                matchesPendingMessage(pending, message) &&
                (local.replyTo?.id ?: 0L) == (message.replyTo?.id ?: 0L)
        }?.message?.id
    }

    private fun matchesPendingMessage(pending: PendingOutgoing, message: ChatMessage): Boolean {
        if (pending.message.payload == message.payload) return true
        val action = pending.action as? PendingAction.Attachment ?: return false
        val payloadJson = runCatching { JSONObject(message.payload) }.getOrNull() ?: return false
        val metaJson = if (payloadJson.optInt("v", 0) >= 3) {
            runCatching {
                decryptAttachmentMeta(message, prefs)
            }.getOrNull()
        } else {
            null
        } ?: return false

        val localSize = File(action.localFilePath).takeIf { it.exists() }?.length() ?: pending.message.localAttachmentSize
        return metaJson.optString("name") == action.fileName &&
            metaJson.optString("mime").ifBlank { action.mime } == action.mime &&
            metaJson.optLong("size") == localSize &&
            metaJson.optLong("durationMs", action.durationMs) == action.durationMs
    }

    override fun onCleared() {
        callManager.release()
        VoiceCallRuntime.updateState(app, VoiceCallUiState())
        VoiceCallRuntime.detach()
        super.onCleared()
    }

    private fun clearPendingTracking(messageId: Long) {
        pendingTimeoutJobs.remove(messageId)?.cancel()
        pendingOutgoing.remove(messageId)?.message?.let(::cleanupPendingAttachment)
    }

    private fun cleanupPendingAttachment(message: ChatMessage) {
        message.localAttachmentPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.delete()
    }

    private fun scheduleNotifications() {
        val request = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "e2ee-chat-poll",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancelNotifications() {
        WorkManager.getInstance(app).cancelUniqueWork("e2ee-chat-poll")
    }

    private fun mergeMessages(existing: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> =
        buildList {
            addAll(existing)
            incoming.forEach { message ->
                resolveMatchingPendingMessageId(message)?.let { pendingId ->
                    clearPendingTracking(pendingId)
                    removeAll { it.id == pendingId }
                }
                removeAll { it.id == message.id }
                add(message)
            }
        }
            .distinctBy { it.id }
            .sortedWith(compareBy<ChatMessage> { it.ts }.thenBy { it.id })
            .filter { it.id < 0L || it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis() }

    private fun identityKey(userCode: String, username: String): String =
        userCode.ifBlank { username }

    private fun rewriteOwnMessagesIdentity(
        messages: List<ChatMessage>,
        userCode: String,
        oldUsername: String,
        newUsername: String,
    ): List<ChatMessage> =
        messages.map { message ->
            val belongsToMe = when {
                userCode.isNotBlank() && message.userCode.isNotBlank() -> message.userCode == userCode
                oldUsername.isNotBlank() -> message.username == oldUsername
                else -> false
            }
            if (!belongsToMe) message else message.copy(
                userCode = userCode.ifBlank { message.userCode },
                username = newUsername
            )
        }

    private fun rewriteCachedOwnMessagesIdentity(
        userCode: String,
        oldUsername: String,
        newUsername: String,
    ) {
        if (userCode.isBlank() && oldUsername.isBlank()) return
        conversationCaches.entries.forEach { entry ->
            val cache = entry.value
            entry.setValue(
                cache.copy(
                    messages = rewriteOwnMessagesIdentity(cache.messages, userCode, oldUsername, newUsername)
                )
            )
        }
    }

    private fun extractMentions(text: String): List<String> =
        Regex("@([A-Za-z0-9_.-]{1,32})").findAll(text).map { it.groupValues[1] }
            .filter { username -> uiState.users.any { it.username == username } }
            .distinct()
            .toList()

    private fun ReplyPreview.toJson(): String = JSONObject()
        .put("id", id)
        .put("username", username)
        .put("color", color)
        .put("preview", preview)
        .toString()

    private fun progressOf(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    private fun currentAppVersion(): String =
        runCatching {
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            packageInfo.versionName?.ifBlank { "0.0.0" } ?: "0.0.0"
        }.getOrDefault("0.0.0")

    private fun releaseFileNameFor(release: AppReleaseInfo): String =
        release.fileName.ifBlank {
            when (release.channel) {
                AppReleaseChannel.STABLE -> "e2eechat-release-${release.version}.apk"
                AppReleaseChannel.PRERELEASE -> "e2eechat-prerelease-${release.version}(pre).apk"
            }
        }

    private fun cleanupInstalledUpdatePackages() {
        val currentVersion = currentAppVersion()
        cleanupVersionedFiles(appUpdateFile(app, "placeholder.apk").parentFile, currentVersion)
        cleanupVersionedFiles(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), currentVersion)
        cleanupVersionedFiles(File(app.cacheDir, "shared"), currentVersion)
        cleanupLegacyDownloadedApks(currentVersion)
    }

    private fun cleanupVersionedFiles(directory: File?, currentVersion: String) {
        directory?.listFiles()?.forEach { file ->
            val version = if (file.extension.equals("apk", true)) {
                inspectApkVersion(file) ?: Regex("v\\d+(?:\\.\\d+)+(?:\\(pre\\))?", RegexOption.IGNORE_CASE).find(file.name)?.value
            } else {
                Regex("v\\d+(?:\\.\\d+)+(?:\\(pre\\))?", RegexOption.IGNORE_CASE).find(file.name)?.value
            }
            if (version != null && compareVersionNames(version, currentVersion) <= 0) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupLegacyDownloadedApks(currentVersion: String) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        runCatching {
            app.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("e2eechat-release-v%.apk", "e2eechat-prerelease-v%.apk"),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val version = Regex("v\\d+(?:\\.\\d+)+(?:\\(pre\\))?", RegexOption.IGNORE_CASE).find(name)?.value ?: continue
                    if (compareVersionNames(version, currentVersion) <= 0) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                        runCatching { app.contentResolver.delete(uri, null, null) }
                    }
                }
            }
        }
    }

    private fun resolveDownloadedRelease(channel: AppReleaseChannel, expectedVersion: String, releaseFileName: String): DecryptedAttachment? {
        val fileName = releaseFileName.ifBlank {
            when (channel) {
                AppReleaseChannel.STABLE -> "e2eechat-release-${expectedVersion}.apk"
                AppReleaseChannel.PRERELEASE -> "e2eechat-prerelease-${expectedVersion}(pre).apk"
            }
        }
        val file = appUpdateFile(app, fileName)
        if (!file.exists()) return null
        val actualVersion = inspectApkVersion(file)
        if (actualVersion == null || compareVersionNames(actualVersion, expectedVersion) != 0) {
            runCatching { file.delete() }
            return null
        }
        return DecryptedAttachment(
            name = fileName,
            mime = "application/vnd.android.package-archive",
            size = file.length(),
            file = file
        )
    }

    private fun inspectApkVersion(file: File): String? =
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
            info?.versionName?.ifBlank { null }
        }.getOrNull()

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = left.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
