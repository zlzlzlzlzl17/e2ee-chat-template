package com.example.chat

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FcmRegistrar(private val context: Context) {
    private val prefs = ChatPreferences(context)
    private val client = OkHttpClient()

    fun registerCurrentTokenIfAvailable(
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        FirebaseApp.initializeApp(context) ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            registerToken(token, authTokenOverride, serverUrlOverride)
        }
    }

    fun unregisterCurrentTokenIfAvailable(
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        FirebaseApp.initializeApp(context) ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            unregisterToken(token, authTokenOverride, serverUrlOverride)
        }
    }

    fun registerToken(
        token: String,
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        val authToken = authTokenOverride ?: prefs.token
        val serverUrl = serverUrlOverride ?: prefs.serverUrl
        if (token.isBlank() || authToken.isBlank() || serverUrl.isBlank()) return
        Thread {
            runCatching {
                val locale = if (prefs.language.equals(AppLanguage.ZH.name, ignoreCase = true)) "zh-CN" else "en-US"
                val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
                val model = Build.MODEL.orEmpty()
                val body = JSONObject()
                    .put("token", token)
                    .put("platform", "android")
                    .put("locale", locale)
                    .put("manufacturer", manufacturer)
                    .put("model", model)
                    .toString()
                val request = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/fcm_register")
                    .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                    .header("Authorization", "Bearer $authToken")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            }
        }.start()
    }

    fun unregisterToken(
        token: String,
        authTokenOverride: String? = null,
        serverUrlOverride: String? = null,
    ) {
        val authToken = authTokenOverride ?: prefs.token
        val serverUrl = serverUrlOverride ?: prefs.serverUrl
        if (token.isBlank() || authToken.isBlank() || serverUrl.isBlank()) return
        Thread {
            runCatching {
                val body = JSONObject()
                    .put("token", token)
                    .toString()
                val request = Request.Builder()
                    .url("${serverUrl.trimEnd('/')}/api/fcm_unregister")
                    .header(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                    .header("Authorization", "Bearer $authToken")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            }
        }.start()
    }
}
