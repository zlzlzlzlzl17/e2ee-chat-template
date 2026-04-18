package com.example.chat

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class E2eeChatFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmRegistrar(applicationContext).registerToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotificationCenter.ensureChannel(this)
        val data = message.data
        when (data["type"]) {
            "incoming_call" -> {
                val invite = PendingIncomingCallInvite(
                    conversationId = data["conversation_id"]?.toLongOrNull() ?: 0L,
                    peerUserCode = data["peer_user_code"].orEmpty(),
                    peerUsername = data["peer_username"].orEmpty(),
                    createdAt = data["created_at"]?.toLongOrNull() ?: System.currentTimeMillis(),
                )
                if (invite.conversationId > 0L && invite.peerUsername.isNotBlank()) {
                    ChatPreferences(applicationContext).setPendingIncomingCall(invite)
                    NotificationCenter.showIncomingCallNotification(
                        context = this,
                        invite = invite,
                        localeTag = data["locale"].orEmpty()
                    )
                }
                return
            }
            "call_hangup" -> {
                val prefs = ChatPreferences(applicationContext)
                val conversationId = data["conversation_id"]?.toLongOrNull() ?: 0L
                if (conversationId > 0L && prefs.pendingIncomingCall()?.conversationId == conversationId) {
                    prefs.setPendingIncomingCall(null)
                }
                NotificationCenter.clearIncomingCallNotification(this)
                return
            }
        }
        val unreadCount = data["unread_count"]?.toIntOrNull() ?: 1
        val locale = data["locale"].orEmpty()
        val title = data["title"] ?: message.notification?.title
        val body = data["body"] ?: message.notification?.body
        NotificationCenter.showUnreadNotification(
            context = this,
            unreadCount = unreadCount,
            localeTag = locale,
            titleOverride = title,
            bodyOverride = body
        )
    }
}
