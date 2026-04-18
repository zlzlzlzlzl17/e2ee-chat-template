# Android Registration Integration

This note is intentionally separate from the main template code. It documents one practical way to add an account registration entry to the Android app later, without changing the current public template behavior.


## Current Backend Behavior

The server already exposes:

- `POST /register_request`

Request body:

```json
{
  "username": "alice",
  "password": "safe-enough-password"
}
```

Validation and responses:

- `400 { "error": "invalid_registration_data" }`
  - username is invalid, or password is shorter than 8 characters
- `409 { "error": "username_taken" }`
  - an existing user already has that username
- `409 { "error": "request_already_pending" }`
  - the same username already has a pending registration request
- `200 { "ok": true, "status": "pending" }`
  - the request was accepted and is waiting for admin review

Admin review is already handled on the server side:

- `POST /manage_api/registrations/:id/approve`
- `POST /manage_api/registrations/:id/reject`

The management bootstrap payload also already includes `pending_registration_requests`, so the existing management side can review requests without adding a new server feature.

## User Identity Model

This project currently has three different user identifiers, and it is worth documenting them separately:

- `username`
  - the human-facing login name
  - used by `POST /api/login`
  - also used when submitting `POST /register_request`
  - unique, but not the same thing as the internal database id
- `id`
  - the internal numeric database primary key in the `users` table
  - mainly used by the server and management APIs
  - for example, management-side user update routes use `/manage_api/users/:id/...`
- `user_code`
  - an app-facing generated code stored on the user record
  - returned by login and included in many app payloads
  - better suited than raw database id when you need a stable external user reference

In short:

- login is by `username + password`
- admin/user management on the server side often targets numeric `id`
- the mobile app commonly receives and displays `user_code` plus `username`

If you add Android-side registration, the registration form should ask for `username` and `password` only. The user should not be asked for an `id` or `user_code` during signup.

## Recommended Product Behavior

For the current project shape, the simplest Android UX is:

1. Keep the existing login screen.
2. Add a secondary action such as `Request access` or `Register`.
3. Let the user submit `username + password`.
4. Show a success message like `Request submitted. Wait for admin approval.`
5. Return the user to the login state instead of auto-logging in.

That matches the existing backend contract. The backend only returns `pending`; it does not create a usable session token at registration time.

## Files To Change

Minimal client-side integration only needs these files:

- `e2ee_chat_app/app/src/main/java/com/example/chat/ChatCore.kt`
  - add the registration request API call
- `e2ee_chat_app/app/src/main/java/com/example/chat/ChatViewModel.kt`
  - add UI state and a `requestRegistration(...)` action
- `e2ee_chat_app/app/src/main/java/com/example/chat/MainActivity.kt`
  - add a register entry on the login screen
- `e2ee_chat_app/app/src/main/java/com/example/chat/AppStrings.kt`
  - add button labels and success/error copy

No server change is required for the basic request-and-approve flow.

## A Sample Method

This is a small change set:

- reuse the existing username and password fields on the login screen
- add a second button under `Login`
- send the same two values to `/register_request`
- display a pending-review message when successful

This keeps the login screen simple

## 1. ChatCore.kt

Add a small result model and a new API method next to `login(...)`.

```kotlin
data class RegistrationRequestResult(
    val status: String
)

fun requestRegistration(serverUrl: String, username: String, password: String): RegistrationRequestResult {
    val body = JSONObject()
        .put("username", username)
        .put("password", password)
        .toString()

    val request = requestBuilder("${base(serverUrl)}/register_request")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    execute(request).use { response ->
        val json = JSONObject(response.body!!.string())
        return RegistrationRequestResult(
            status = json.optString("status", "pending")
        )
    }
}
```

Explaination:

- `execute(...)` already throws `IllegalStateException(parseError(response))` on non-2xx responses
- `parseError(...)` already extracts backend error values such as `invalid_registration_data` and `username_taken`
- the implementation style matches the existing `login(...)` method

## 2. ChatViewModel.kt

Add a dedicated success message to UI state so registration success does not need to reuse `error`.

Example state addition:

```kotlin
data class ChatUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val roomSecret: String = "",
    val e2eeEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.systemDefault(),
    val displayMode: AppDisplayMode = AppDisplayMode.SYSTEM,
    val dynamicColorsEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isConnected: Boolean = false,
    val me: ChatUser? = null,
    val users: List<ChatUser> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
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
    val voiceCall: VoiceCallUiState = VoiceCallUiState(),
    val error: String? = null,
    val registrationMessage: String? = null,
)
```

Then add an action similar to `login(...)`:

```kotlin
fun requestRegistration(username: String, password: String) {
    viewModelScope.launch {
        uiState = uiState.copy(isLoading = true, error = null, registrationMessage = null)
        runCatching {
            withContext(Dispatchers.IO) {
                api.requestRegistration(uiState.serverUrl, username.trim(), password)
            }
        }.onSuccess {
            uiState = uiState.copy(
                isLoading = false,
                error = null,
                registrationMessage = "Request submitted. Wait for admin approval."
            )
        }.onFailure {
            uiState = uiState.copy(
                isLoading = false,
                registrationMessage = null,
                error = mapRegistrationError(it.message)
            )
        }
    }
}

private fun mapRegistrationError(raw: String?): String {
    return when (raw) {
        "invalid_registration_data" -> "Use a valid username and a password with at least 8 characters."
        "username_taken" -> "That username is already in use."
        "request_already_pending" -> "A request for that username is already waiting for approval."
        else -> raw ?: "Registration request failed"
    }
}
```

Recommended detail:

- do not save login credentials during registration
- do not mark the user as logged in
- do not call `loadBootstrap()`

Registration is only a request submission step in the current backend design.

After the request is approved, the first normal login still uses:

- `username`
- `password`

The app then receives:

- `token`
- `user_code`
- `username`

That means the registration screen and the login screen should keep treating `username` as the credential input, even though the backend internally tracks users by numeric `id`.

## 3. MainActivity.kt

The current login screen already has:

- username input
- password input
- login button
- inline error text

The easiest UI change is to add:

- one secondary `TextButton`
- one success text area for `registrationMessage`

Minimal idea:

```kotlin
Button(
    onClick = { viewModel.login(username.trim(), password) },
    enabled = !state.isLoading,
    modifier = Modifier.fillMaxWidth()
) {
    if (state.isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        Text(strings.login)
    }
}

TextButton(
    onClick = { viewModel.requestRegistration(username.trim(), password) },
    enabled = !state.isLoading,
    modifier = Modifier.fillMaxWidth()
) {
    Text(strings.requestAccess)
}

state.registrationMessage?.let {
    Text(it, color = MaterialTheme.colorScheme.primary)
}

state.error?.let {
    Text(it, color = MaterialTheme.colorScheme.error)
}
```

If you want slightly better UX, use an `AlertDialog` for registration instead of reusing the login form directly. The backend contract stays the same either way.

## 4. AppStrings.kt

Add user-facing copy for both languages.

Suggested keys:

```kotlin
val requestAccess: String,
val registrationPending: String,
val registrationInvalid: String,
val registrationUsernameTaken: String,
val registrationAlreadyPending: String,
```

Example English values:

```kotlin
requestAccess = "Request access",
registrationPending = "Request submitted. Wait for admin approval.",
registrationInvalid = "Use a valid username and a password with at least 8 characters.",
registrationUsernameTaken = "That username is already in use.",
registrationAlreadyPending = "A request for that username is already waiting for approval.",
```

Use the same pattern for any locale you want to support. The current template ships with English and Chinese as example locales only.

## Suggested Implementation Order

1. Add `requestRegistration(...)` in `ChatCore.kt`.
2. Add `registrationMessage` and `requestRegistration(...)` in `ChatViewModel.kt`.
3. Add labels in `AppStrings.kt`.
4. Add a second action in `LoginScreen(...)` in `MainActivity.kt`.
5. Test against a local server:
   - invalid username
   - password shorter than 8 characters
   - existing username
   - duplicate pending request
   - successful pending request
   - admin approval followed by normal login
   - confirm that approved users still log in with `username`, not with numeric `id` or `user_code`
