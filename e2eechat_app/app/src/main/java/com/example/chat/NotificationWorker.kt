package com.example.chat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        NotificationCenter.ensureChannel(applicationContext)
        val prefs = ChatPreferences(applicationContext)
        if (prefs.token.isBlank() || prefs.serverUrl.isBlank()) return@withContext Result.success()

        val api = ChatApi()
        val conversations = runCatching {
            api.conversations(prefs.serverUrl, prefs.token)
        }.getOrElse { emptyList() }
        if (conversations.isEmpty()) return@withContext Result.success()

        val batches = mutableListOf<Pair<ConversationSummary, ChatMessage>>()
        for (conversation in conversations) {
            val history = runCatching {
                api.history(
                    serverUrl = prefs.serverUrl,
                    token = prefs.token,
                    conversationId = conversation.id,
                    sinceId = prefs.lastNotifiedMessageId,
                    limit = 100
                ).items
            }.getOrElse { emptyList() }
            history.forEach { batches += conversation to it }
        }
        if (batches.isEmpty()) return@withContext Result.success()

        val latestId = batches.maxOf { it.second.id }
        if (prefs.lastNotifiedMessageId == 0L) {
            prefs.lastNotifiedMessageId = latestId
            return@withContext Result.success()
        }

        val myUserCode = prefs.userCode
        val myUsername = prefs.username
        val incoming = batches
            .filter { (_, message) ->
                isNotifiableChatKind(message.kind) && !isOwnMessage(message, myUserCode, myUsername)
            }
            .sortedBy { (_, message) -> message.id }
        if (incoming.isNotEmpty()) {
            val localeTag = if (prefs.language.equals(AppLanguage.ZH.name, ignoreCase = true)) "zh-CN" else "en-US"
            NotificationCenter.showUnreadNotification(
                context = applicationContext,
                unreadCount = incoming.size,
                localeTag = localeTag
            )
        }

        prefs.lastNotifiedMessageId = latestId
        Result.success()
    }

    companion object {
        private fun isNotifiableChatKind(kind: String): Boolean =
            when (kind) {
                "text", "image", "photo", "file", "audio" -> true
                else -> false
            }

        private fun isOwnMessage(message: ChatMessage, myUserCode: String, myUsername: String): Boolean =
            when {
                myUserCode.isNotBlank() && message.userCode.isNotBlank() -> message.userCode == myUserCode
                myUsername.isNotBlank() -> message.username == myUsername
                else -> false
            }
    }
}
