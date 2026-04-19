package com.example.chat

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

data class CallIceServerConfig(
    val urls: List<String>,
    val username: String = "",
    val credential: String = "",
)

enum class VoiceAudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
}

enum class VoiceCallPhase {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTING,
    ACTIVE,
    ENDED,
    FAILED,
}

data class VoiceCallUiState(
    val phase: VoiceCallPhase = VoiceCallPhase.IDLE,
    val conversationId: Long = 0L,
    val peerUserCode: String = "",
    val peerUsername: String = "",
    val isIncoming: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val currentAudioRoute: VoiceAudioRoute = VoiceAudioRoute.EARPIECE,
    val availableAudioRoutes: List<VoiceAudioRoute> = listOf(VoiceAudioRoute.EARPIECE, VoiceAudioRoute.SPEAKER),
    val startedAt: Long = 0L,
    val statusMessage: String = "",
)

class VoiceCallManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onSignal(type: String, payload: JSONObject)
        fun onStateChanged(state: VoiceCallUiState)
        fun onError(message: String)
    }

    private var currentState = VoiceCallUiState()
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var previousMicrophoneMute = false
    private var audioSessionActive = false
    private var isClearingPeerConnection = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var iceServers: List<CallIceServerConfig> = defaultIceServers()
    private val audioDeviceCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    onAudioDevicesChanged()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    onAudioDevicesChanged()
                }
            }
        } else {
            null
        }
    private val audioDeviceCallbackHandler = Handler(Looper.getMainLooper())
    private var audioDeviceCallbackRegistered = false
    private var disconnectTimeoutRunnable: Runnable? = null
    private val disconnectGracePeriodMs = 25_000L
    private var signalingReconnectPending = false
    private var signalingConnected = true
    private var iceRestartInFlight = false
    private var lastIceRestartAt = 0L
    private val minIceRestartIntervalMs = 4_000L
    private val logTag = "E2eeChatCall"

    fun state(): VoiceCallUiState = currentState

    fun updateIceServers(servers: List<CallIceServerConfig>) {
        iceServers = if (servers.isNotEmpty()) servers else defaultIceServers()
        debugLog("ice_servers_updated", "count=${iceServers.size}")
    }

    fun startOutgoing(conversationId: Long, peerUserCode: String, peerUsername: String) {
        if (!canStartNewCall()) return
        debugLog("start_outgoing", "conversationId=$conversationId peer=$peerUsername/$peerUserCode")
        updateState(
            VoiceCallUiState(
                phase = VoiceCallPhase.OUTGOING,
                conversationId = conversationId,
                peerUserCode = peerUserCode,
                peerUsername = peerUsername,
                statusMessage = "calling"
            )
        )
        callbacks.onSignal(
            "call_invite",
            JSONObject().put("conversation_id", conversationId)
        )
    }

    fun receiveIncomingInvite(conversationId: Long, peerUserCode: String, peerUsername: String): Boolean {
        debugLog("receive_incoming_invite", "conversationId=$conversationId peer=$peerUsername/$peerUserCode phase=${currentState.phase}")
        if (
            currentState.phase == VoiceCallPhase.INCOMING &&
            currentState.conversationId == conversationId &&
            currentState.peerUsername == peerUsername &&
            currentState.peerUserCode == peerUserCode
        ) {
            return true
        }
        if (currentState.phase != VoiceCallPhase.IDLE) {
            callbacks.onSignal(
                "call_busy",
                JSONObject().put("conversation_id", conversationId)
            )
            return false
        }
        updateState(
            VoiceCallUiState(
                phase = VoiceCallPhase.INCOMING,
                conversationId = conversationId,
                peerUserCode = peerUserCode,
                peerUsername = peerUsername,
                isIncoming = true,
                statusMessage = "incoming"
            )
        )
        return true
    }

    fun restoreIncomingInvite(state: VoiceCallUiState) {
        if (state.phase != VoiceCallPhase.INCOMING || state.conversationId <= 0L) return
        if (
            currentState.phase == VoiceCallPhase.INCOMING &&
            currentState.conversationId == state.conversationId &&
            currentState.peerUsername == state.peerUsername &&
            currentState.peerUserCode == state.peerUserCode
        ) {
            return
        }
        if (currentState.phase != VoiceCallPhase.IDLE) return
        updateState(
            state.copy(
                phase = VoiceCallPhase.INCOMING,
                isIncoming = true,
                statusMessage = if (state.statusMessage.isBlank()) "incoming" else state.statusMessage,
            )
        )
    }

    fun acceptIncoming() {
        if (currentState.phase != VoiceCallPhase.INCOMING) return
        if (!preparePeerConnection()) return
        debugLog("accept_incoming", "conversationId=${currentState.conversationId}")
        updateState(currentState.copy(phase = VoiceCallPhase.CONNECTING, statusMessage = "connecting"))
        callbacks.onSignal(
            "call_accept",
            JSONObject().put("conversation_id", currentState.conversationId)
        )
    }

    fun rejectIncoming() {
        if (currentState.phase != VoiceCallPhase.INCOMING) return
        debugLog("reject_incoming", "conversationId=${currentState.conversationId}")
        callbacks.onSignal(
            "call_reject",
            JSONObject().put("conversation_id", currentState.conversationId)
        )
        clearToIdle()
    }

    fun onRemoteAccepted() {
        if (currentState.phase != VoiceCallPhase.OUTGOING) return
        if (!preparePeerConnection()) return
        debugLog("remote_accepted", "conversationId=${currentState.conversationId}")
        updateState(currentState.copy(phase = VoiceCallPhase.CONNECTING, statusMessage = "connecting"))
        createOffer()
    }

    fun onRemoteRejected() {
        debugLog("remote_rejected", "conversationId=${currentState.conversationId}")
        finishWithTerminalState(VoiceCallPhase.ENDED, "rejected")
    }

    fun onRemoteBusy() {
        debugLog("remote_busy", "conversationId=${currentState.conversationId}")
        finishWithTerminalState(VoiceCallPhase.ENDED, "busy")
    }

    fun onRemoteUnavailable() {
        debugLog("remote_unavailable", "conversationId=${currentState.conversationId}")
        finishWithTerminalState(VoiceCallPhase.ENDED, "unavailable")
    }

    fun onRemoteHangup() {
        debugLog("remote_hangup", "conversationId=${currentState.conversationId}")
        finishWithTerminalState(VoiceCallPhase.ENDED, "ended")
    }

    fun onRemoteOffer(description: JSONObject) {
        debugLog("remote_offer", "conversationId=${currentState.conversationId} phase=${currentState.phase} type=${description.optString("type")} sdpLength=${description.optString("sdp").length}")
        if (
            currentState.phase != VoiceCallPhase.CONNECTING &&
            currentState.phase != VoiceCallPhase.INCOMING &&
            currentState.phase != VoiceCallPhase.ACTIVE
        ) return
        if (!preparePeerConnection()) return
        iceRestartInFlight = false
        val recoveringExistingCall = currentState.startedAt > 0L || currentState.phase == VoiceCallPhase.ACTIVE
        val remoteDescription = description.toSessionDescription() ?: return
        peerConnection?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    debugLog("remote_offer_set_success", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
                    remoteDescriptionSet = true
                    flushPendingIceCandidates()
                    createAnswer(recoveringExistingCall)
                }

                override fun onSetFailure(message: String?) {
                    iceRestartInFlight = false
                    warnLog("remote_offer_set_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                    callbacks.onError(message ?: "Unable to accept call offer")
                    if (recoveringExistingCall) {
                        enterReconnectWindow()
                    } else {
                        finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                    }
                }
            },
            remoteDescription
        )
    }

    fun onRemoteAnswer(description: JSONObject) {
        debugLog("remote_answer", "conversationId=${currentState.conversationId} type=${description.optString("type")} sdpLength=${description.optString("sdp").length}")
        val remoteDescription = description.toSessionDescription() ?: return
        peerConnection?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    iceRestartInFlight = false
                    debugLog("remote_answer_set_success", "conversationId=${currentState.conversationId}")
                    remoteDescriptionSet = true
                    flushPendingIceCandidates()
                }

                override fun onSetFailure(message: String?) {
                    iceRestartInFlight = false
                    warnLog("remote_answer_set_failure", "conversationId=${currentState.conversationId} error=${message ?: "unknown"}")
                    callbacks.onError(message ?: "Unable to accept call answer")
                    finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                }
            },
            remoteDescription
        )
    }

    fun onRemoteIceCandidate(candidateJson: JSONObject) {
        debugLog(
            "remote_ice_candidate",
            "conversationId=${currentState.conversationId} mid=${candidateJson.optString("sdpMid")} line=${candidateJson.optInt("sdpMLineIndex", -1)} remoteDescriptionSet=$remoteDescriptionSet"
        )
        val candidate = candidateJson.toIceCandidate() ?: return
        if (peerConnection == null || !remoteDescriptionSet) {
            pendingIceCandidates += candidate
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun toggleMute() {
        val nextMuted = !currentState.isMuted
        localAudioTrack?.setEnabled(!nextMuted)
        audioManager.isMicrophoneMute = nextMuted
        updateState(currentState.copy(isMuted = nextMuted))
    }

    fun toggleSpeaker() {
        val nextRoute = if (currentState.currentAudioRoute == VoiceAudioRoute.SPEAKER) {
            if (currentState.availableAudioRoutes.contains(VoiceAudioRoute.BLUETOOTH)) VoiceAudioRoute.BLUETOOTH else VoiceAudioRoute.EARPIECE
        } else {
            VoiceAudioRoute.SPEAKER
        }
        selectAudioRoute(nextRoute)
    }

    fun selectAudioRoute(route: VoiceAudioRoute) {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(route) -> route
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        updateState(
            currentState.copy(
                currentAudioRoute = resolvedRoute,
                isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
                availableAudioRoutes = availableRoutes
            )
        )
        E2eeChatTelecom.requestAudioRoute(resolvedRoute)
        if (audioSessionActive) applyCurrentAudioRoute()
    }

    fun syncSystemAudioRoute(route: VoiceAudioRoute) {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(route) -> route
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        if (
            currentState.currentAudioRoute == resolvedRoute &&
            currentState.isSpeakerOn == (resolvedRoute == VoiceAudioRoute.SPEAKER) &&
            currentState.availableAudioRoutes == availableRoutes
        ) {
            return
        }
        debugLog(
            "sync_system_audio_route",
            "reported=$route resolved=$resolvedRoute available=$availableRoutes"
        )
        updateState(
            currentState.copy(
                currentAudioRoute = resolvedRoute,
                isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
                availableAudioRoutes = availableRoutes,
            )
        )
    }

    fun endCall(sendSignal: Boolean = true) {
        val conversationId = currentState.conversationId
        debugLog("end_call", "conversationId=$conversationId sendSignal=$sendSignal phase=${currentState.phase}")
        if (sendSignal && conversationId > 0L && currentState.phase != VoiceCallPhase.IDLE) {
            callbacks.onSignal(
                "call_hangup",
                JSONObject().put("conversation_id", conversationId)
            )
        }
        clearToIdle()
    }

    fun dismissTerminalState() {
        if (currentState.phase == VoiceCallPhase.ENDED || currentState.phase == VoiceCallPhase.FAILED) {
            clearToIdle()
        }
    }

    fun onSignalingDisconnected() {
        signalingConnected = false
        warnLog("signaling_disconnected", "conversationId=${currentState.conversationId} phase=${currentState.phase}")
        if (currentState.phase != VoiceCallPhase.IDLE && currentState.phase != VoiceCallPhase.ENDED) {
            signalingReconnectPending = true
            enterReconnectWindow("reconnecting")
        }
    }

    fun onSignalingReconnected() {
        signalingConnected = true
        debugLog("signaling_reconnected", "conversationId=${currentState.conversationId} phase=${currentState.phase} reconnectPending=$signalingReconnectPending")
        if (!signalingReconnectPending) return
        signalingReconnectPending = false
        if (currentState.phase == VoiceCallPhase.IDLE || currentState.phase == VoiceCallPhase.ENDED || currentState.phase == VoiceCallPhase.FAILED) {
            return
        }
        if (currentState.statusMessage == "reconnecting") {
            maybeRequestIceRestart()
        }
    }

    fun release() {
        debugLog("release", "conversationId=${currentState.conversationId}")
        clearPeerConnection()
        clearToIdle()
        audioDeviceModule?.release()
        audioDeviceModule = null
        factory?.dispose()
        factory = null
    }

    private fun canStartNewCall(): Boolean {
        if (currentState.phase != VoiceCallPhase.IDLE) {
            warnLog("start_blocked", "phase=${currentState.phase} conversationId=${currentState.conversationId}")
            callbacks.onError("Call already in progress")
            return false
        }
        return true
    }

    private fun preparePeerConnection(): Boolean {
        ensureFactory()
        ensureAudioSession()
        ensureLocalAudioTrack()
        if (peerConnection != null) return true
        debugLog("prepare_peer_connection", "conversationId=${currentState.conversationId}")
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        val config = PeerConnection.RTCConfiguration(iceServers.toPeerConnectionServers())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    debugLog("local_ice_candidate", "conversationId=${currentState.conversationId} mid=${candidate.sdpMid} line=${candidate.sdpMLineIndex}")
                    callbacks.onSignal(
                        "call_ice",
                        JSONObject()
                            .put("conversation_id", currentState.conversationId)
                            .put(
                                "candidate",
                                JSONObject()
                                    .put("sdpMid", candidate.sdpMid)
                                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                    .put("candidate", candidate.sdp)
                            )
                    )
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (isClearingPeerConnection) return
                    debugLog("peer_connection_state", "conversationId=${currentState.conversationId} state=$newState phase=${currentState.phase} status=${currentState.statusMessage}")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            cancelDisconnectTimeout()
                            iceRestartInFlight = false
                            if (currentState.phase != VoiceCallPhase.ACTIVE || currentState.statusMessage != "active") {
                                updateState(
                                    currentState.copy(
                                        phase = VoiceCallPhase.ACTIVE,
                                        startedAt = currentState.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                        statusMessage = "active"
                                    )
                                )
                            }
                        }

                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            if (
                                currentState.phase != VoiceCallPhase.IDLE &&
                                currentState.phase != VoiceCallPhase.ENDED &&
                                currentState.phase != VoiceCallPhase.FAILED
                            ) {
                                enterReconnectWindow()
                            }
                        }

                        else -> Unit
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (isClearingPeerConnection) return
                    debugLog("ice_connection_state", "conversationId=${currentState.conversationId} state=$newState phase=${currentState.phase} status=${currentState.statusMessage}")
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            cancelDisconnectTimeout()
                            iceRestartInFlight = false
                            if (currentState.phase != VoiceCallPhase.ACTIVE || currentState.statusMessage != "active") {
                                updateState(
                                    currentState.copy(
                                        phase = VoiceCallPhase.ACTIVE,
                                        startedAt = currentState.startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                        statusMessage = "active"
                                    )
                                )
                            }
                        }

                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> enterReconnectWindow()

                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    if (isClearingPeerConnection) return
                    debugLog("ice_receiving_change", "conversationId=${currentState.conversationId} receiving=$receiving phase=${currentState.phase}")
                    if (!receiving && currentState.startedAt > 0L && currentState.phase == VoiceCallPhase.ACTIVE) {
                        enterReconnectWindow()
                    }
                }
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
            }
        )
        val track = localAudioTrack ?: return false
        peerConnection?.addTrack(track)
        return peerConnection != null
    }

    private fun createOffer(iceRestart: Boolean = false) {
        val connection = peerConnection ?: return
        debugLog("create_offer", "conversationId=${currentState.conversationId} iceRestart=$iceRestart")
        if (iceRestart) {
            runCatching { connection.restartIce() }
        }
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    debugLog("create_offer_success", "conversationId=${currentState.conversationId} type=${description.type} sdpLength=${description.description.length}")
                    connection.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                debugLog("set_local_offer_success", "conversationId=${currentState.conversationId} iceRestart=$iceRestart")
                                callbacks.onSignal(
                                    "call_offer",
                                    JSONObject()
                                        .put("conversation_id", currentState.conversationId)
                                        .put(
                                            "description",
                                            JSONObject()
                                                .put("type", description.type.canonicalForm())
                                                .put("sdp", description.description)
                                        )
                                )
                            }

                            override fun onSetFailure(message: String?) {
                                if (iceRestart) iceRestartInFlight = false
                                warnLog("set_local_offer_failure", "conversationId=${currentState.conversationId} iceRestart=$iceRestart error=${message ?: "unknown"}")
                                callbacks.onError(message ?: "Unable to create call offer")
                                if (iceRestart) {
                                    enterReconnectWindow()
                                } else {
                                    finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                                }
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(message: String?) {
                    if (iceRestart) iceRestartInFlight = false
                    warnLog("create_offer_failure", "conversationId=${currentState.conversationId} iceRestart=$iceRestart error=${message ?: "unknown"}")
                    callbacks.onError(message ?: "Unable to create call offer")
                    if (iceRestart) {
                        enterReconnectWindow()
                    } else {
                        finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                    }
                }
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                if (iceRestart) {
                    mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                }
            }
        )
    }

    private fun createAnswer(recoveringExistingCall: Boolean = false) {
        val connection = peerConnection ?: return
        debugLog("create_answer", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
        connection.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description == null) return
                    debugLog("create_answer_success", "conversationId=${currentState.conversationId} type=${description.type} sdpLength=${description.description.length}")
                    connection.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                debugLog("set_local_answer_success", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall")
                                callbacks.onSignal(
                                    "call_answer",
                                    JSONObject()
                                        .put("conversation_id", currentState.conversationId)
                                        .put(
                                            "description",
                                            JSONObject()
                                                .put("type", description.type.canonicalForm())
                                                .put("sdp", description.description)
                                        )
                                )
                            }

                            override fun onSetFailure(message: String?) {
                                iceRestartInFlight = false
                                warnLog("set_local_answer_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                                callbacks.onError(message ?: "Unable to create call answer")
                                if (recoveringExistingCall) {
                                    enterReconnectWindow()
                                } else {
                                    finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                                }
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(message: String?) {
                    iceRestartInFlight = false
                    warnLog("create_answer_failure", "conversationId=${currentState.conversationId} recovering=$recoveringExistingCall error=${message ?: "unknown"}")
                    callbacks.onError(message ?: "Unable to create call answer")
                    if (recoveringExistingCall) {
                        enterReconnectWindow()
                    } else {
                        finishWithTerminalState(VoiceCallPhase.FAILED, "failed")
                    }
                }
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
        )
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    private fun ensureLocalAudioTrack() {
        if (localAudioTrack != null) return
        val peerFactory = factory ?: return
        localAudioSource = peerFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerFactory.createAudioTrack("e2ee_chat_audio_track", localAudioSource).also {
            it.setEnabled(!currentState.isMuted)
        }
    }

    private fun ensureAudioSession() {
        if (audioSessionActive) return
        previousAudioMode = audioManager.mode
        previousSpeakerphone = audioManager.isSpeakerphoneOn
        previousMicrophoneMute = audioManager.isMicrophoneMute
        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = currentState.isMuted
        audioSessionActive = true
        registerAudioDeviceCallback()
        refreshAvailableAudioRoutes()
        applyCurrentAudioRoute()
    }

    private fun restoreAudioSession() {
        if (!audioSessionActive) return
        unregisterAudioDeviceCallback()
        clearPreferredCommunicationDevice()
        stopBluetoothRouting()
        audioManager.mode = previousAudioMode
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.isMicrophoneMute = previousMicrophoneMute
        abandonAudioFocus()
        audioSessionActive = false
    }

    private fun applyCurrentAudioRoute() {
        when (currentState.currentAudioRoute) {
            VoiceAudioRoute.SPEAKER -> {
                stopBluetoothRouting()
                clearPreferredCommunicationDevice()
                val preferredSpeaker = preferBuiltInSpeakerRoute()
                audioManager.isSpeakerphoneOn = true
                debugLog(
                    "apply_audio_route",
                    "target=speaker preferred=$preferredSpeaker device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
                )
                return
            }

            VoiceAudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                if (preferBluetoothRoute()) return
                val fallbackRoute =
                    if (computeAvailableAudioRoutes().contains(VoiceAudioRoute.EARPIECE)) VoiceAudioRoute.EARPIECE else VoiceAudioRoute.SPEAKER
                updateState(
                    currentState.copy(
                        currentAudioRoute = fallbackRoute,
                        isSpeakerOn = fallbackRoute == VoiceAudioRoute.SPEAKER
                    )
                )
                applyCurrentAudioRoute()
                return
            }

            VoiceAudioRoute.EARPIECE -> Unit
        }

        clearPreferredCommunicationDevice()
        audioManager.isSpeakerphoneOn = false
        if (!hasBuiltInEarpiece()) {
            stopBluetoothRouting()
            audioManager.isSpeakerphoneOn = true
            debugLog(
                "apply_audio_route",
                "target=earpiece_fallback_to_speaker device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
            )
            return
        }

        stopBluetoothRouting()
        val preferredEarpiece = preferBuiltInEarpieceRoute()
        if (!preferredEarpiece) {
            clearPreferredCommunicationDevice()
        }
        debugLog(
            "apply_audio_route",
            "target=earpiece preferred=$preferredEarpiece device=${currentCommunicationDeviceSummary()} speakerphoneOn=${audioManager.isSpeakerphoneOn}"
        )
    }

    private fun preferBuiltInSpeakerRoute(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        return runCatching { audioManager.setCommunicationDevice(speakerDevice) }.getOrDefault(false)
    }

    private fun preferBuiltInEarpieceRoute(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val earpieceDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        } ?: return false
        return runCatching { audioManager.setCommunicationDevice(earpieceDevice) }.getOrDefault(false)
    }

    private fun preferBluetoothRoute(): Boolean {
        if (!audioManager.isBluetoothScoAvailableOffCall) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            } ?: return false
            runCatching { audioManager.setCommunicationDevice(bluetoothDevice) }.getOrDefault(false)
        } else {
            runCatching { audioManager.startBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = true }
            true
        }
    }

    private fun stopBluetoothRouting() {
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.isBluetoothScoOn = false }
    }

    private fun computeAvailableAudioRoutes(): List<VoiceAudioRoute> {
        val routes = mutableListOf<VoiceAudioRoute>()
        if (hasBuiltInEarpiece()) routes += VoiceAudioRoute.EARPIECE
        if (hasBluetoothRoute()) routes += VoiceAudioRoute.BLUETOOTH
        routes += VoiceAudioRoute.SPEAKER
        return routes.distinct()
    }

    private fun hasBuiltInEarpiece(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            true
        }

    private fun hasBluetoothRoute(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            audioManager.isBluetoothScoOn
        }

    private fun clearPreferredCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
    }

    private fun currentCommunicationDeviceSummary(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { device ->
                "${device.type}:${device.productName}"
            } ?: "none"
        } else {
            "legacy"
        }

    private fun onAudioDevicesChanged() {
        if (!audioSessionActive) return
        val routeChanged = refreshAvailableAudioRoutes()
        if (routeChanged) {
            applyCurrentAudioRoute()
        }
    }

    private fun refreshAvailableAudioRoutes(): Boolean {
        val availableRoutes = computeAvailableAudioRoutes()
        val resolvedRoute = when {
            availableRoutes.contains(currentState.currentAudioRoute) -> currentState.currentAudioRoute
            availableRoutes.contains(VoiceAudioRoute.EARPIECE) -> VoiceAudioRoute.EARPIECE
            else -> VoiceAudioRoute.SPEAKER
        }
        val nextState = currentState.copy(
            isSpeakerOn = resolvedRoute == VoiceAudioRoute.SPEAKER,
            currentAudioRoute = resolvedRoute,
            availableAudioRoutes = availableRoutes
        )
        val routeChanged = resolvedRoute != currentState.currentAudioRoute
        if (nextState != currentState) {
            currentState = nextState
            callbacks.onStateChanged(nextState)
        }
        return routeChanged
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !audioDeviceCallbackRegistered) {
            audioDeviceCallback?.let { callback ->
                runCatching { audioManager.registerAudioDeviceCallback(callback, audioDeviceCallbackHandler) }
                audioDeviceCallbackRegistered = true
            }
        }
    }

    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallbackRegistered) {
            audioDeviceCallback?.let { callback ->
                runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
            }
            audioDeviceCallbackRegistered = false
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { audioFocusRequest = it }
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                runCatching { audioManager.abandonAudioFocusRequest(request) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
    }

    private fun clearPeerConnection() {
        if (isClearingPeerConnection) return
        isClearingPeerConnection = true
        debugLog("clear_peer_connection", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
        cancelDisconnectTimeout()
        signalingReconnectPending = false
        signalingConnected = true
        iceRestartInFlight = false
        pendingIceCandidates.clear()
        remoteDescriptionSet = false
        val connection = peerConnection
        val audioTrack = localAudioTrack
        val audioSource = localAudioSource
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        try {
            runCatching { connection?.close() }
            runCatching { connection?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
        } finally {
            restoreAudioSession()
            isClearingPeerConnection = false
        }
    }

    private fun flushPendingIceCandidates() {
        val connection = peerConnection ?: return
        val iterator = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        iterator.forEach(connection::addIceCandidate)
    }

    private fun finishWithTerminalState(phase: VoiceCallPhase, statusMessage: String) {
        warnLog("finish_terminal_state", "conversationId=${currentState.conversationId} phase=$phase reason=$statusMessage")
        clearPeerConnection()
        updateState(
            currentState.copy(
                phase = phase,
                statusMessage = statusMessage
            )
        )
    }

    private fun clearToIdle() {
        debugLog("clear_to_idle", "conversationId=${currentState.conversationId}")
        clearPeerConnection()
        updateState(VoiceCallUiState())
    }

    private fun enterReconnectWindow(statusMessage: String = "reconnecting") {
        if (
            currentState.phase == VoiceCallPhase.IDLE ||
            currentState.phase == VoiceCallPhase.ENDED ||
            currentState.phase == VoiceCallPhase.FAILED
        ) return
        warnLog("enter_reconnect_window", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage} startedAt=${currentState.startedAt}")
        val reconnectPhase =
            if (currentState.startedAt > 0L || currentState.phase == VoiceCallPhase.ACTIVE) {
                VoiceCallPhase.ACTIVE
            } else {
                VoiceCallPhase.CONNECTING
            }
        if (currentState.phase != reconnectPhase || currentState.statusMessage != statusMessage) {
            updateState(
                currentState.copy(
                    phase = reconnectPhase,
                    statusMessage = statusMessage
                )
            )
        }
        scheduleDisconnectTimeout()
        maybeRequestIceRestart()
    }

    private fun scheduleDisconnectTimeout() {
        cancelDisconnectTimeout()
        debugLog("schedule_disconnect_timeout", "conversationId=${currentState.conversationId} delayMs=$disconnectGracePeriodMs")
        disconnectTimeoutRunnable = Runnable {
            if (currentState.phase != VoiceCallPhase.IDLE && currentState.phase != VoiceCallPhase.ENDED) {
                warnLog("disconnect_timeout_fired", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
                finishWithTerminalState(VoiceCallPhase.FAILED, "connection_lost")
            }
        }.also { runnable ->
            audioDeviceCallbackHandler.postDelayed(runnable, disconnectGracePeriodMs)
        }
    }

    private fun cancelDisconnectTimeout() {
        if (disconnectTimeoutRunnable != null) {
            debugLog("cancel_disconnect_timeout", "conversationId=${currentState.conversationId}")
        }
        disconnectTimeoutRunnable?.let(audioDeviceCallbackHandler::removeCallbacks)
        disconnectTimeoutRunnable = null
    }

    private fun updateState(next: VoiceCallUiState) {
        val availableRoutes = computeAvailableAudioRoutes()
        val desiredRoute = if (availableRoutes.contains(next.currentAudioRoute)) {
            next.currentAudioRoute
        } else if (availableRoutes.contains(VoiceAudioRoute.EARPIECE)) {
            VoiceAudioRoute.EARPIECE
        } else {
            VoiceAudioRoute.SPEAKER
        }
        val decorated = next.copy(
            isSpeakerOn = desiredRoute == VoiceAudioRoute.SPEAKER,
            currentAudioRoute = desiredRoute,
            availableAudioRoutes = availableRoutes
        )
        if (decorated != currentState) {
            debugLog(
                "state_updated",
                "conversationId=${decorated.conversationId} phase=${currentState.phase}->${decorated.phase} status=${currentState.statusMessage}->${decorated.statusMessage} incoming=${decorated.isIncoming} startedAt=${decorated.startedAt}"
            )
        }
        currentState = decorated
        callbacks.onStateChanged(decorated)
    }

    private fun maybeRequestIceRestart() {
        if (peerConnection == null) return
        if (!signalingConnected) return
        if (currentState.isIncoming) return
        if (!remoteDescriptionSet) return
        if (
            currentState.phase == VoiceCallPhase.IDLE ||
            currentState.phase == VoiceCallPhase.ENDED ||
            currentState.phase == VoiceCallPhase.FAILED
        ) return
        val now = System.currentTimeMillis()
        if (iceRestartInFlight || now - lastIceRestartAt < minIceRestartIntervalMs) return
        lastIceRestartAt = now
        iceRestartInFlight = true
        warnLog("request_ice_restart", "conversationId=${currentState.conversationId} phase=${currentState.phase} status=${currentState.statusMessage}")
        createOffer(iceRestart = true)
    }

    private fun defaultIceServers(): List<CallIceServerConfig> =
        listOf(CallIceServerConfig(urls = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")))

    private fun debugLog(event: String, message: String) {
        Log.d(logTag, "$event | $message")
    }

    private fun warnLog(event: String, message: String) {
        Log.w(logTag, "$event | $message")
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(message: String?) = Unit
    override fun onSetFailure(message: String?) = Unit
}

private fun List<CallIceServerConfig>.toPeerConnectionServers(): List<PeerConnection.IceServer> =
    flatMap { config ->
        config.urls.map { url ->
            PeerConnection.IceServer.builder(url)
                .setUsername(config.username)
                .setPassword(config.credential)
                .createIceServer()
        }
    }

private fun JSONObject.toSessionDescription(): SessionDescription? {
    val typeText = optString("type").trim()
    val sdp = optString("sdp")
    if (typeText.isBlank() || sdp.isBlank()) return null
    val type = when (typeText.lowercase()) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> return null
    }
    return SessionDescription(type, sdp)
}

private fun JSONObject.toIceCandidate(): IceCandidate? {
    val sdpMid = optString("sdpMid").ifBlank { null }
    val sdpMLineIndex = optInt("sdpMLineIndex", -1)
    val candidate = optString("candidate")
    if (sdpMid == null || sdpMLineIndex < 0 || candidate.isBlank()) return null
    return IceCandidate(sdpMid, sdpMLineIndex, candidate)
}
