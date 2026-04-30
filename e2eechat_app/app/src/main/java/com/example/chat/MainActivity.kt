package com.example.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chat.ui.theme.ChatTheme
import com.example.chat.ui.theme.WaPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private enum class AppScreen { Home, Chat, Settings, SettingsGeneral, SettingsAbout, SettingsSecurity, AccountManage, PeerManage, GroupManage, Prerelease }
private val WaReadReceiptBlue = Color(0xFF34B7F1)
private data class PendingAvatarCrop(
    val fileName: String,
    val mime: String,
    val bytes: ByteArray,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationCenter.ensureChannel(this)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            ChatTheme(
                displayMode = vm.uiState.displayMode,
                dynamicColor = vm.uiState.dynamicColorsEnabled
            ) {
                E2eeChatApp(vm)
            }
        }
    }
}

@Composable
private fun E2eeChatApp(viewModel: ChatViewModel) {
    val state = viewModel.uiState
    val voiceCallMinimized by VoiceCallRuntime.isMinimized.collectAsState()
    val strings = stringsFor(state.language)
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home.name) }
    val currentScreen = runCatching { AppScreen.valueOf(screen) }.getOrDefault(AppScreen.Home)
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentVersion = remember(context) { currentAppVersion(context) }
    var lastAutoUpdateCheckAt by rememberSaveable { mutableLongStateOf(0L) }
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf("") }
    var lastBackPressAt by rememberSaveable { mutableLongStateOf(0L) }
    var pendingVoiceConversationId by rememberSaveable { mutableLongStateOf(0L) }
    var pendingVoiceAccept by rememberSaveable { mutableStateOf(false) }
    var selectedPeerUserCode by rememberSaveable { mutableStateOf("") }
    val hasOngoingVoiceCall =
        state.voiceCall.phase != VoiceCallPhase.IDLE &&
            state.voiceCall.phase != VoiceCallPhase.ENDED &&
            state.voiceCall.phase != VoiceCallPhase.FAILED
    val latestRelease = state.latestAppRelease
    val shouldShowUpdatePrompt = state.isLoggedIn &&
        latestRelease != null &&
        latestRelease.version.isNotBlank() &&
        compareVersionNames(latestRelease.version, currentVersion) > 0 &&
        dismissedUpdateVersion != latestRelease.version
    val voicePermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            when {
                pendingVoiceAccept -> viewModel.acceptIncomingCall()
                pendingVoiceConversationId > 0L -> viewModel.startVoiceCall(pendingVoiceConversationId)
            }
        } else {
            viewModel.reportError(strings.microphonePermissionDenied)
        }
        pendingVoiceAccept = false
        pendingVoiceConversationId = 0L
    }

    fun triggerVersionCheck(force: Boolean = false) {
        if (!state.isLoggedIn || state.isCheckingUpdate) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoUpdateCheckAt < 5 * 60 * 1000L) return
        lastAutoUpdateCheckAt = now
        viewModel.checkForUpdates()
    }

    fun startVoiceCallWithPermission(conversationId: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startVoiceCall(conversationId)
        } else {
            pendingVoiceAccept = false
            pendingVoiceConversationId = conversationId
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun acceptVoiceCallWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.acceptIncomingCall()
        } else {
            pendingVoiceAccept = true
            pendingVoiceConversationId = 0L
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun minimizeVoiceCall() {
        VoiceCallRuntime.setMinimized(context, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, strings.overlayPermissionHint, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) triggerVersionCheck(force = true)
    }

    LaunchedEffect(state.relationshipMessage) {
        if (!state.relationshipMessage.isNullOrBlank()) {
            delay(5_000)
            viewModel.clearRelationshipMessage()
        }
    }

    DisposableEffect(lifecycleOwner, state.isLoggedIn) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    VoiceCallRuntime.setAppForeground(context, true)
                    NotificationCenter.clearAll(context)
                    viewModel.onAppForeground()
                    triggerVersionCheck()
                }
                Lifecycle.Event.ON_STOP -> {
                    VoiceCallRuntime.setAppForeground(context, false)
                    viewModel.onAppBackground()
                    if (hasOngoingVoiceCall) {
                        VoiceCallRuntime.setMinimized(context, true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (shouldShowUpdatePrompt) {
        AnimatedActionDialog(
            title = strings.updatePromptTitle,
            body = {
                Text(
                    buildString {
                        append(strings.updatePromptBody)
                        latestRelease?.version?.takeIf { it.isNotBlank() }?.let {
                            append("\n\n")
                            append(strings.latestVersion)
                            append(": ")
                            append(it)
                        }
                    }
                )
            },
            confirmText = strings.updateNow,
            onConfirm = {
                screen = AppScreen.Settings.name
                dismissedUpdateVersion = latestRelease!!.version
            },
            dismissText = strings.later,
            onDismissRequest = { dismissedUpdateVersion = latestRelease!!.version }
        )
    }

    if (!state.isLoggedIn) {
        LoginScreen(viewModel, state)
        return
    }

    BackHandler {
        when (currentScreen) {
            AppScreen.Chat -> screen = AppScreen.Home.name
            AppScreen.Settings -> screen = AppScreen.Home.name
            AppScreen.SettingsGeneral, AppScreen.SettingsAbout, AppScreen.SettingsSecurity -> screen = AppScreen.Settings.name
            AppScreen.AccountManage -> screen = AppScreen.Home.name
            AppScreen.PeerManage, AppScreen.GroupManage -> screen = AppScreen.Chat.name
            AppScreen.Prerelease -> screen = AppScreen.SettingsAbout.name
            AppScreen.Home -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressAt < 1800L) {
                    if (hasOngoingVoiceCall) {
                        VoiceCallRuntime.setMinimized(context, true)
                        activity?.moveTaskToBack(true)
                    } else {
                        activity?.finish()
                    }
                } else {
                    lastBackPressAt = now
                    Toast.makeText(context, strings.backAgainToExit, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                when {
                    targetState == AppScreen.Settings || initialState == AppScreen.Settings -> {
                        (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith
                            (fadeOut() + scaleOut(targetScale = 0.96f))
                    }
                    initialState == AppScreen.Home && targetState == AppScreen.Chat -> {
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 4 } + fadeOut())
                    }
                    initialState == AppScreen.Chat && targetState == AppScreen.Home -> {
                        (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                    }
                    else -> fadeIn() togetherWith fadeOut()
                }.using(SizeTransform(clip = false))
            },
            label = "screenNavigation",
            modifier = Modifier.fillMaxSize()
        ) { targetScreen ->
            when (targetScreen) {
                AppScreen.Home -> HomeScreen(
                    viewModel = viewModel,
                    state = state,
                    onOpenChat = {
                        viewModel.openConversation(it)
                        screen = AppScreen.Chat.name
                    },
                    onOpenSettings = { screen = AppScreen.Settings.name },
                    onOpenAccount = { screen = AppScreen.AccountManage.name }
                )
                AppScreen.Chat -> ChatScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Home.name },
                    onStartVoiceCall = ::startVoiceCallWithPermission,
                    onOpenPeerManage = { userCode ->
                        selectedPeerUserCode = userCode.orEmpty()
                        screen = AppScreen.PeerManage.name
                    },
                    onOpenGroupManage = {
                        viewModel.refreshCurrentConversationManage()
                        screen = AppScreen.GroupManage.name
                    }
                )
                AppScreen.Settings -> SettingsMenuScreen(
                    state = state,
                    onBack = { screen = AppScreen.Home.name },
                    onOpenGeneral = { screen = AppScreen.SettingsGeneral.name },
                    onOpenAbout = { screen = AppScreen.SettingsAbout.name },
                    onOpenSecurity = { screen = AppScreen.SettingsSecurity.name }
                )
                AppScreen.SettingsGeneral -> SettingsGeneralScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Settings.name }
                )
                AppScreen.SettingsAbout -> SettingsAboutScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Settings.name },
                    onOpenPrerelease = { screen = AppScreen.Prerelease.name }
                )
                AppScreen.SettingsSecurity -> SettingsSecurityScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Settings.name }
                )
                AppScreen.AccountManage -> AccountManageScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Home.name }
                )
                AppScreen.PeerManage -> PeerManageScreen(
                    viewModel = viewModel,
                    state = state,
                    selectedUserCode = selectedPeerUserCode.takeIf { it.isNotBlank() },
                    onBack = {
                        selectedPeerUserCode = ""
                        screen = AppScreen.Chat.name
                    },
                    onOpenDirect = {
                        selectedPeerUserCode = ""
                        viewModel.openConversation(it)
                        screen = AppScreen.Chat.name
                    }
                )
                AppScreen.GroupManage -> GroupManageScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.Chat.name },
                    onOpenMember = { userCode ->
                        selectedPeerUserCode = userCode
                        screen = AppScreen.PeerManage.name
                    }
                )
                AppScreen.Prerelease -> PrereleaseScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { screen = AppScreen.SettingsAbout.name }
                )
            }
        }

        state.voiceCall.takeIf { it.phase != VoiceCallPhase.IDLE }?.let { callState ->
            if (voiceCallMinimized && callState.phase != VoiceCallPhase.ENDED && callState.phase != VoiceCallPhase.FAILED) {
                VoiceCallMiniBubbleLayer(
                    callState = callState,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                    onExpand = { VoiceCallRuntime.setMinimized(context, false) },
                    onEnd = viewModel::endVoiceCall
                )
            } else {
                VoiceCallScreen(
                    callState = callState,
                    strings = strings,
                    onAccept = ::acceptVoiceCallWithPermission,
                    onDecline = viewModel::declineIncomingCall,
                    onEnd = viewModel::endVoiceCall,
                    onToggleMute = viewModel::toggleVoiceCallMute,
                    onSelectAudioRoute = viewModel::setVoiceCallAudioRoute,
                    onDismissTerminal = viewModel::dismissVoiceCallStatus,
                    onMinimize = ::minimizeVoiceCall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onBack: (() -> Unit)? = null,
    navigationAvatar: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null || navigationAvatar != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                    if (navigationAvatar != null) {
                        Box(modifier = Modifier.padding(start = if (onBack != null) 0.dp else 8.dp)) {
                            navigationAvatar()
                        }
                    }
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CenteredAppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun ConfigureDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.setBackgroundDrawable(ColorDrawable(Color.Transparent.toArgb()))
        window?.setDimAmount(0f)
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
        onDispose { }
    }
}

@Composable
private fun AnimatedDialogContainer(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { visible = true }
    val dismissAnimated = remember(onDismissRequest) {
        {
            scope.launch {
                visible = false
                delay(140)
                onDismissRequest()
            }
            Unit
        }
    }

    Dialog(onDismissRequest = dismissAnimated, properties = properties) {
        ConfigureDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = contentAlignment
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.96f)
            ) {
                content(dismissAnimated)
            }
        }
    }
}

@Composable
private fun AnimatedActionDialog(
    title: String,
    body: @Composable () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismissRequest: () -> Unit,
) {
    AnimatedDialogContainer(onDismissRequest = onDismissRequest) { dismiss ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                body()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = dismiss) { Text(dismissText) }
                    TextButton(onClick = {
                        dismiss()
                        onConfirm()
                    }) {
                        Text(confirmText)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallScreen(
    callState: VoiceCallUiState,
    strings: AppStrings,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectAudioRoute: (VoiceAudioRoute) -> Unit,
    onDismissTerminal: () -> Unit,
    onMinimize: () -> Unit,
) {
    var audioOutputMenuExpanded by remember(callState.currentAudioRoute, callState.availableAudioRoutes) { mutableStateOf(false) }
    val dismissAction = {
        when (callState.phase) {
            VoiceCallPhase.INCOMING -> onDecline()
            VoiceCallPhase.ENDED, VoiceCallPhase.FAILED -> onDismissTerminal()
            else -> onMinimize()
        }
    }
    BackHandler { dismissAction() }
    val durationText by produceState(initialValue = "00:00", callState.startedAt, callState.phase) {
        if (callState.phase != VoiceCallPhase.ACTIVE || callState.startedAt <= 0L) {
            value = "00:00"
            return@produceState
        }
        while (true) {
            val elapsedSeconds = ((System.currentTimeMillis() - callState.startedAt) / 1000L).coerceAtLeast(0L)
            val minutes = elapsedSeconds / 60L
            val seconds = elapsedSeconds % 60L
            value = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            delay(1_000L)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0F1824),
                            Color(0xFF091018),
                            Color(0xFF06080C)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (callState.phase != VoiceCallPhase.ENDED && callState.phase != VoiceCallPhase.FAILED) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            IconButton(onClick = onMinimize) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = strings.minimizeCall,
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AvatarBubble(
                        imageUrl = "",
                        fallback = callState.peerUsername.ifBlank { strings.voiceCall },
                        colorHex = "#128c7e",
                        size = 96.dp
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            callState.peerUsername.ifBlank { strings.voiceCall },
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = callState.statusMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFB8C4D6)
                        )
                        if (callState.phase == VoiceCallPhase.ACTIVE) {
                            Text(
                                text = durationText,
                                color = Color(0xFF6FD7C8),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }

                when (callState.phase) {
                    VoiceCallPhase.INCOMING -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onDecline,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF3A1F23),
                                    contentColor = Color(0xFFFFC8CC)
                                )
                            ) {
                                Text(strings.decline)
                            }
                            Button(
                                onClick = onAccept,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(strings.accept)
                            }
                        }
                    }

                    VoiceCallPhase.ENDED, VoiceCallPhase.FAILED -> {
                        Button(
                            onClick = onDismissTerminal,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.cancel)
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onToggleMute,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (callState.isMuted) strings.unmute else strings.mute)
                                }

                                Box(modifier = Modifier.weight(1f)) {
                                    FilledTonalButton(
                                        onClick = { audioOutputMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color.White.copy(alpha = 0.1f),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            when (callState.currentAudioRoute) {
                                                VoiceAudioRoute.BLUETOOTH -> Icons.Default.VolumeOff
                                                VoiceAudioRoute.SPEAKER -> Icons.Default.VolumeUp
                                                VoiceAudioRoute.EARPIECE -> Icons.Default.VolumeOff
                                            },
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.Start,
                                            verticalArrangement = Arrangement.spacedBy(1.dp)
                                        ) {
                                            Text(strings.audioOutput, style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                audioRouteLabel(callState.currentAudioRoute, strings),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.audioOutput)
                                    }
                                    DropdownMenu(
                                        expanded = audioOutputMenuExpanded,
                                        onDismissRequest = { audioOutputMenuExpanded = false }
                                    ) {
                                        callState.availableAudioRoutes.forEach { route ->
                                            DropdownMenuItem(
                                                text = { Text(audioRouteLabel(route, strings)) },
                                                onClick = {
                                                    audioOutputMenuExpanded = false
                                                    onSelectAudioRoute(route)
                                                },
                                                trailingIcon = {
                                                    if (route == callState.currentAudioRoute) {
                                                        Icon(Icons.Default.Done, contentDescription = null)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = onEnd,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB3261E),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.endCall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCallMiniBubbleLayer(
    callState: VoiceCallUiState,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onEnd: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val bubbleWidth = 220.dp
    val bubbleHeight = 90.dp
    val bubbleWidthPx = with(density) { bubbleWidth.roundToPx() }
    val bubbleHeightPx = with(density) { bubbleHeight.roundToPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val edgePaddingPx = with(density) { 12.dp.roundToPx() }
    val bottomInsetPx = with(density) { 110.dp.roundToPx() }
    val maxOffsetX = (screenWidthPx - bubbleWidthPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    val maxOffsetY = (screenHeightPx - bubbleHeightPx - edgePaddingPx).coerceAtLeast(edgePaddingPx)
    var offsetX by rememberSaveable(screenWidthPx) {
        mutableIntStateOf(maxOffsetX)
    }
    var offsetY by rememberSaveable(screenHeightPx) {
        mutableIntStateOf((screenHeightPx - bubbleHeightPx - bottomInsetPx).coerceIn(edgePaddingPx, maxOffsetY))
    }

    LaunchedEffect(maxOffsetX, maxOffsetY, edgePaddingPx) {
        offsetX = offsetX.coerceIn(edgePaddingPx, maxOffsetX)
        offsetY = offsetY.coerceIn(edgePaddingPx, maxOffsetY)
    }

    Box(modifier = modifier) {
        VoiceCallMiniBubble(
            callState = callState,
            strings = strings,
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .pointerInput(maxOffsetX, maxOffsetY) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x.roundToInt()).coerceIn(edgePaddingPx, maxOffsetX)
                        offsetY = (offsetY + dragAmount.y.roundToInt()).coerceIn(edgePaddingPx, maxOffsetY)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExpand() })
                },
            onEnd = onEnd
        )
    }
}

@Composable
private fun VoiceCallMiniBubble(
    callState: VoiceCallUiState,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    onEnd: () -> Unit,
) {
    val durationText by produceState(initialValue = "00:00", callState.startedAt, callState.phase) {
        if (callState.phase != VoiceCallPhase.ACTIVE || callState.startedAt <= 0L) {
            value = callState.statusMessage
            return@produceState
        }
        while (true) {
            val elapsedSeconds = ((System.currentTimeMillis() - callState.startedAt) / 1000L).coerceAtLeast(0L)
            value = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
            delay(1_000L)
        }
    }
    ElevatedCard(
        modifier = modifier.widthIn(min = 180.dp, max = 240.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xEE101A27)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarBubble(
                imageUrl = "",
                fallback = callState.peerUsername.ifBlank { strings.voiceCall },
                colorHex = "#128c7e",
                size = 42.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    callState.peerUsername.ifBlank { strings.voiceCall },
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
                Text(
                    if (callState.phase == VoiceCallPhase.ACTIVE) durationText else callState.statusMessage,
                    color = Color(0xFFB8C4D6),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    strings.returnToCall,
                    color = Color(0xFF6FD7C8),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            FilledTonalButton(
                onClick = onEnd,
                modifier = Modifier.height(38.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF3A1F23),
                    contentColor = Color(0xFFFFC8CC)
                )
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = strings.endCall)
            }
        }
    }
}

private fun audioRouteLabel(route: VoiceAudioRoute, strings: AppStrings): String =
    when (route) {
        VoiceAudioRoute.EARPIECE -> strings.earpiece
        VoiceAudioRoute.SPEAKER -> strings.speaker
        VoiceAudioRoute.BLUETOOTH -> strings.bluetooth
    }

@Composable
private fun rememberBottomBounceConnection(
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomBounceOffset: Animatable<Float, AnimationVector1D>
): NestedScrollConnection {
    val scope = rememberCoroutineScope()
    return remember(listState, bottomBounceOffset) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val hasScrollableContent = listState.canScrollBackward ||
                    listState.canScrollForward ||
                    listState.layoutInfo.totalItemsCount > listState.layoutInfo.visibleItemsInfo.size
                if (!hasScrollableContent) return Offset.Zero

                val pullingPastBottom = !listState.canScrollForward && available.y < 0f
                if (pullingPastBottom) {
                    scope.launch {
                        val next = (bottomBounceOffset.value + available.y * 0.18f).coerceIn(-96f, 0f)
                        bottomBounceOffset.snapTo(next)
                    }
                } else if (bottomBounceOffset.value != 0f && available.y > 0f) {
                    scope.launch {
                        val next = (bottomBounceOffset.value + available.y * 0.22f).coerceIn(-96f, 0f)
                        bottomBounceOffset.snapTo(next)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (bottomBounceOffset.value != 0f) {
                    bottomBounceOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                return Velocity.Zero
            }
        }
    }
}

@Composable
private fun LoginScreen(viewModel: ChatViewModel, state: ChatUiState) {
    val strings = stringsFor(state.language)
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    val passwordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    fun submit() {
        keyboardController?.hide()
        if (registerMode) {
            viewModel.requestRegistration(username.trim(), password, confirmPassword)
        } else {
            viewModel.login(username.trim(), password)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LanguageSelector(state.language, viewModel::updateLanguage, strings.language)
                    Text(
                        if (registerMode) strings.createAccount else strings.e2eeChat,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(strings.appSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(strings.username) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { passwordFocusRequester.requestFocus() }
                        )
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(strings.password) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (registerMode) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                submit()
                            },
                            onNext = {
                                confirmPasswordFocusRequester.requestFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocusRequester),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (registerMode) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text(strings.confirmPassword) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(confirmPasswordFocusRequester),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    Button(onClick = { submit() }, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (registerMode) strings.createAccount else strings.login)
                        }
                    }
                    TextButton(
                        onClick = {
                            registerMode = !registerMode
                            viewModel.clearError()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (registerMode) strings.backToLogin else strings.register)
                    }
                    state.registrationMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onOpenChat: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val conversations = remember(state.conversations) { state.conversations.sortedByDescending { it.lastMessageTs } }
    val onRefresh = remember(viewModel) { { viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true) } }
    val listState = rememberLazyListState()
    val bottomBounceOffset = remember(state.conversations.hashCode()) { Animatable(0f) }
    val bounceConnection = rememberBottomBounceConnection(listState, bottomBounceOffset)
    val statusColor = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }

    if (showAddContactDialog) {
        AddContactDialog(
            state = state,
            strings = strings,
            onSearch = viewModel::lookupUserByCode,
            onRequest = viewModel::requestContact,
            onApprove = { viewModel.reviewContactRequest(it, approve = true) },
            onDismiss = {
                showAddContactDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }
    if (showJoinGroupDialog) {
        JoinGroupDialog(
            state = state,
            strings = strings,
            onSearch = viewModel::lookupGroupByCode,
            onRequest = viewModel::requestJoinGroup,
            onDismiss = {
                showJoinGroupDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            strings = strings,
            onCreate = viewModel::createGroup,
            onDismiss = {
                showCreateGroupDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = strings.e2eeChat,
                subtitle = buildStatusLine(state, strings),
                subtitleColor = statusColor,
                navigationAvatar = {
                    Box(modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenAccount)) {
                        AvatarBubble(
                            state.me?.avatarUrl.orEmpty(),
                            state.me?.username.orEmpty(),
                            state.me?.color ?: "#128c7e",
                            32.dp,
                            serverUrl = state.serverUrl
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionMenuExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = strings.contactsAndGroups)
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.addContact) },
                                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showAddContactDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.joinGroup) },
                                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showJoinGroupDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.newGroup) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showCreateGroupDialog = true
                                }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refresh)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = strings.settings)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            state.uploadProgress?.let { TransferProgressCard(strings.uploading, it) }
            state.downloadProgress?.let { TransferProgressCard(strings.downloading, it) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            state.relationshipMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            PendingRelationshipRequests(
                state = state,
                strings = strings,
                serverUrl = state.serverUrl,
                onApproveContact = { viewModel.reviewContactRequest(it, approve = true) },
                onRejectContact = { viewModel.reviewContactRequest(it, approve = false) },
                onApproveGroup = { viewModel.reviewGroupJoinRequest(it, approve = true) },
                onRejectGroup = { viewModel.reviewGroupJoinRequest(it, approve = false) },
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .nestedScroll(bounceConnection)
                        .offset { IntOffset(0, bottomBounceOffset.value.roundToInt()) },
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            user = state.users.find {
                                (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) ||
                                    it.username == conversation.directUsername
                            },
                            serverUrl = state.serverUrl,
                            onClick = { onOpenChat(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    user: ChatUser?,
    serverUrl: String,
    onClick: () -> Unit,
) {
    val avatarShape = if (conversation.kind == "group") RoundedCornerShape(14.dp) else CircleShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBubble(
                conversation.avatarUrl.ifBlank { user?.avatarUrl.orEmpty() },
                conversation.title,
                user?.color ?: "#128c7e",
                52.dp,
                serverUrl = serverUrl,
                shape = avatarShape
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    conversation.lastMessagePreview.ifBlank { " " },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (conversation.lastMessageTs > 0) {
                    Text(
                        formatMessageTime(conversation.lastMessageTs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                if (conversation.unreadCount > 0) {
                    Badge { Text(conversation.unreadCount.toString()) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun AddContactDialog(
    state: ChatUiState,
    strings: AppStrings,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onApprove: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var userCode by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addContact) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = userCode,
                    onValueChange = { userCode = it.filter { char -> char.isDigit() }.take(8) },
                    label = { Text(strings.userId) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(userCode) }),
                    modifier = Modifier.fillMaxWidth()
                )
                state.relationshipMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                state.userLookupResult?.let { result ->
                    ContactLookupResultCard(
                        result = result,
                        strings = strings,
                        serverUrl = state.serverUrl,
                        onRequest = { onRequest(result.user.userCode) },
                        onApprove = { onApprove(result.incomingRequestId) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(userCode) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.search)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun ContactLookupResultCard(
    result: UserLookupResult,
    strings: AppStrings,
    serverUrl: String,
    onRequest: () -> Unit,
    onApprove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarBubble(result.user.avatarUrl, result.user.username, result.user.color, 40.dp, serverUrl)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.user.username, style = MaterialTheme.typography.titleSmall)
                    Text("${strings.userId}: ${result.user.userCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            when {
                result.isContact -> Text(strings.alreadyConnected, color = MaterialTheme.colorScheme.primary)
                result.incomingPending -> FilledTonalButton(onClick = onApprove) { Text(strings.approve) }
                result.outgoingPending -> Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> FilledTonalButton(onClick = onRequest) { Text(strings.requestContact) }
            }
        }
    }
}

@Composable
private fun JoinGroupDialog(
    state: ChatUiState,
    strings: AppStrings,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupCode by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroup) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupCode,
                    onValueChange = { groupCode = it.filter { char -> char.isDigit() }.take(10) },
                    label = { Text(strings.groupId) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(groupCode) }),
                    modifier = Modifier.fillMaxWidth()
                )
                state.relationshipMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                state.groupLookupResult?.let { result ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleSmall)
                            Text("${strings.groupCode}: ${result.groupCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            when {
                                result.isMember -> Text(strings.alreadyInGroup, color = MaterialTheme.colorScheme.primary)
                                result.pending -> Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> FilledTonalButton(onClick = { onRequest(result.groupCode) }) { Text(strings.requestJoin) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(groupCode) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.search)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun CreateGroupDialog(
    strings: AppStrings,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newGroup) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(64) },
                label = { Text(strings.groupName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title) }) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun PendingRelationshipRequests(
    state: ChatUiState,
    strings: AppStrings,
    serverUrl: String,
    onApproveContact: (Long) -> Unit,
    onRejectContact: (Long) -> Unit,
    onApproveGroup: (Long) -> Unit,
    onRejectGroup: (Long) -> Unit,
) {
    val contactIncoming = state.contactRequests.filter { it.direction == "incoming" }
    val contactOutgoing = state.contactRequests.filter { it.direction == "outgoing" }
    val groupIncoming = state.groupJoinRequests.filter { it.direction == "incoming" }
    val groupOutgoing = state.groupJoinRequests.filter { it.direction == "outgoing" }
    if (contactIncoming.isEmpty() && contactOutgoing.isEmpty() && groupIncoming.isEmpty() && groupOutgoing.isEmpty()) return

    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(strings.contactsAndGroups, style = MaterialTheme.typography.titleSmall)
        (contactIncoming + contactOutgoing).forEach { request ->
            RequestCard(
                title = request.user.username,
                subtitle = "${strings.userId}: ${request.user.userCode}",
                avatarUrl = request.user.avatarUrl,
                avatarTitle = request.user.username,
                color = request.user.color,
                serverUrl = serverUrl,
                incoming = request.direction == "incoming",
                strings = strings,
                onApprove = { onApproveContact(request.id) },
                onReject = { onRejectContact(request.id) }
            )
        }
        (groupIncoming + groupOutgoing).forEach { request ->
            RequestCard(
                title = request.title,
                subtitle = "${strings.groupCode}: ${request.groupCode}  ${request.requester.username}",
                avatarUrl = request.avatarUrl,
                avatarTitle = request.title,
                color = request.requester.color,
                serverUrl = serverUrl,
                shape = RoundedCornerShape(10.dp),
                incoming = request.direction == "incoming",
                strings = strings,
                onApprove = { onApproveGroup(request.id) },
                onReject = { onRejectGroup(request.id) }
            )
        }
    }
}

@Composable
private fun RequestCard(
    title: String,
    subtitle: String,
    avatarUrl: String,
    avatarTitle: String,
    color: String,
    serverUrl: String,
    shape: Shape = CircleShape,
    incoming: Boolean,
    strings: AppStrings,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarBubble(avatarUrl, avatarTitle, color, 36.dp, serverUrl, shape = shape)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (!incoming) Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (incoming) {
                IconButton(onClick = onApprove) {
                    Icon(Icons.Default.Check, contentDescription = strings.approve)
                }
                IconButton(onClick = onReject) {
                    Icon(Icons.Default.Close, contentDescription = strings.reject)
                }
            }
        }
    }
}

@Composable
private fun PeerSafetyCodeCard(
    identities: List<DevicePublicIdentity>,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strings.peerSafetyCode, style = MaterialTheme.typography.labelLarge)
            if (identities.isEmpty()) {
                Text(strings.noPeerSafetyCode, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                val identity = identities.first()
                Text(identity.safetyCode, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                Text(identity.deviceName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsMenuScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSecurity: () -> Unit,
) {
    val strings = stringsFor(state.language)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.settings, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManagementSection {
                ManagementRow(
                    title = strings.general,
                    subtitle = strings.displayMode,
                    leading = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenGeneral
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ManagementRow(
                    title = strings.about,
                    subtitle = strings.currentVersion,
                    leading = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenAbout
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ManagementRow(
                    title = strings.security,
                    subtitle = strings.safetyCode,
                    leading = { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenSecurity
                )
            }
        }
    }
}

@Composable
private fun SettingsEntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManagementSection(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
private fun ManagementRow(
    title: String,
    subtitle: String = "",
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = contentColor, style = MaterialTheme.typography.bodyLarge)
            subtitle.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ExpandableHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    ManagementRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = onClick
    )
}

@Composable
private fun SettingsGeneralScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.general, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(strings.language, style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.language == AppLanguage.ZH,
                            onClick = { viewModel.updateLanguage(AppLanguage.ZH) },
                            label = { Text("\u4e2d\u6587", style = MaterialTheme.typography.bodyLarge) }
                        )
                        FilterChip(
                            selected = state.language == AppLanguage.EN,
                            onClick = { viewModel.updateLanguage(AppLanguage.EN) },
                            label = { Text("English", style = MaterialTheme.typography.bodyLarge) }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(strings.displayMode, style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.displayMode == AppDisplayMode.SYSTEM,
                            onClick = { viewModel.updateDisplayMode(AppDisplayMode.SYSTEM) },
                            label = { Text(strings.followSystem, style = MaterialTheme.typography.bodyLarge) }
                        )
                        FilterChip(
                            selected = state.displayMode == AppDisplayMode.LIGHT,
                            onClick = { viewModel.updateDisplayMode(AppDisplayMode.LIGHT) },
                            label = { Text(strings.lightMode, style = MaterialTheme.typography.bodyLarge) }
                        )
                        FilterChip(
                            selected = state.displayMode == AppDisplayMode.DARK,
                            onClick = { viewModel.updateDisplayMode(AppDisplayMode.DARK) },
                            label = { Text(strings.darkMode, style = MaterialTheme.typography.bodyLarge) }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.systemColors, style = MaterialTheme.typography.titleMedium)
                        Text(
                            strings.systemColorsHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = state.dynamicColorsEnabled,
                        onCheckedChange = viewModel::updateDynamicColorsEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAboutScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenPrerelease: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember(context) { currentAppVersion(context) }
    val release = state.latestAppRelease
    val updateAvailable = release != null && compareVersionNames(release.version, currentVersion) > 0
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.about, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${strings.currentVersion}: $currentVersion", style = MaterialTheme.typography.titleMedium)
                    Text("${strings.latestVersion}: ${releaseDisplayVersion(release)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        state.updateStatus ?: if (release == null) strings.noReleaseUploaded else if (updateAvailable) strings.updateAvailable else strings.latestVersionInstalled,
                        color = if (updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.downloadProgress?.let { progress ->
                        LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = viewModel::checkForUpdates, enabled = !state.isCheckingUpdate) {
                            Text(if (state.isCheckingUpdate) strings.checkingUpdate else strings.checkUpdate)
                        }
                        FilledTonalButton(
                            onClick = { scope.launch { downloadLatestUpdate(context, viewModel) } },
                            enabled = updateAvailable && state.downloadProgress == null
                        ) { Text(strings.downloadUpdate) }
                    }
                    state.downloadedUpdate?.let {
                        FilledTonalButton(onClick = { openInstaller(context, viewModel, it) }, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.openInstaller)
                        }
                    }
                    FilledTonalButton(onClick = onOpenPrerelease, modifier = Modifier.fillMaxWidth()) {
                        Text(strings.prereleaseBuilds)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSecurityScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    var pendingRemoveDeviceId by rememberSaveable { mutableStateOf("") }
    val pendingRemoveDevice = state.myDevices.find { it.deviceId == pendingRemoveDeviceId }
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) viewModel.refreshMyDevices()
    }
    if (pendingRemoveDeviceId.isNotBlank()) {
        val label = pendingRemoveDevice?.deviceName?.takeIf { it.isNotBlank() } ?: pendingRemoveDeviceId.take(8)
        AnimatedActionDialog(
            title = strings.removeDevice,
            body = { Text(strings.removeDeviceConfirm.format(label)) },
            confirmText = strings.removeDevice,
            onConfirm = {
                viewModel.removeMyDevice(pendingRemoveDeviceId)
                pendingRemoveDeviceId = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = { pendingRemoveDeviceId = "" }
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.security, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManagementSection(title = strings.deviceSecurity) {
                state.deviceIdentity?.let { identity ->
                    Text("${strings.deviceName}: ${identity.deviceName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${strings.deviceId}: ${identity.deviceId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(identity.safetyCode, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    Text(strings.safetyCodeHint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } ?: Text(strings.noDeviceIdentity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = state.e2eeEnabled, onCheckedChange = viewModel::updateE2eeEnabled)
                    Text(strings.e2ee)
                }
            }

            ManagementSection(title = strings.devices) {
                if (state.myDevices.isEmpty()) {
                    Text(strings.noDeviceIdentity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.myDevices.forEachIndexed { index, device ->
                        val isCurrent = device.deviceId == state.deviceIdentity?.deviceId
                        val deviceName = device.deviceName.ifBlank { device.deviceId.take(8) }
                        val subtitle = buildString {
                            append("${strings.deviceId}: ${device.deviceId}")
                            if (device.lastSeenAt > 0L) {
                                append("\n")
                                append("${strings.lastSeen}: ${formatTimelineLabel(device.lastSeenAt, state.language, strings)}")
                            }
                        }
                        ManagementRow(
                            title = deviceName,
                            subtitle = subtitle,
                            leading = {
                                Icon(
                                    imageVector = if (isCurrent) Icons.Default.Done else Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailing = {
                                if (isCurrent) {
                                    Text(strings.currentDevice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                } else {
                                    TextButton(onClick = { pendingRemoveDeviceId = device.deviceId }) {
                                        Text(strings.removeDevice)
                                    }
                                }
                            }
                        )
                        if (index != state.myDevices.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountManageScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showDeletionDialog by remember { mutableStateOf(false) }
    var pendingAvatarCrop by remember { mutableStateOf<PendingAvatarCrop?>(null) }
    var passwordDraft by rememberSaveable { mutableStateOf("") }
    var usernameDraft by rememberSaveable(state.me?.username) { mutableStateOf(state.me?.username.orEmpty()) }
    val avatarPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "avatar.jpg"
        pendingAvatarCrop = PendingAvatarCrop(
            fileName = name,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            bytes = bytes
        )
    }

    if (showPasswordDialog) {
        SimpleTextInputDialog(
            title = strings.changePassword,
            label = strings.newPassword,
            initial = "",
            password = true,
            strings = strings,
            onDismiss = { showPasswordDialog = false },
            onSave = {
                passwordDraft = it
                viewModel.changePassword(passwordDraft)
                passwordDraft = ""
                showPasswordDialog = false
            }
        )
    }
    if (showUsernameDialog) {
        SimpleTextInputDialog(
            title = strings.changeUsername,
            label = strings.newUsername,
            initial = usernameDraft,
            password = false,
            strings = strings,
            onDismiss = { showUsernameDialog = false },
            onSave = {
                usernameDraft = it
                viewModel.changeUsername(usernameDraft)
                showUsernameDialog = false
            }
        )
    }
    if (showDeletionDialog) {
        AnimatedActionDialog(
            title = strings.requestAccountDeletion,
            body = { Text(strings.requestAccountDeletionConfirm) },
            confirmText = strings.requestAccountDeletion,
            onConfirm = {
                showDeletionDialog = false
                viewModel.requestAccountDeletion()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showDeletionDialog = false }
        )
    }
    pendingAvatarCrop?.let { crop ->
        AvatarCropDialog(
            title = strings.changeAvatar,
            source = crop,
            strings = strings,
            onDismiss = { pendingAvatarCrop = null },
            onConfirm = { croppedBytes ->
                viewModel.uploadAvatar("avatar.jpg", "image/jpeg", croppedBytes)
                pendingAvatarCrop = null
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.accountManage, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManagementSection {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AvatarBubble(state.me?.avatarUrl.orEmpty(), state.me?.username.orEmpty(), state.me?.color ?: "#128c7e", 76.dp, state.serverUrl)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.me?.username.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                        state.me?.userCode?.takeIf { it.isNotBlank() }?.let {
                            Text("${strings.userId}: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            ManagementSection {
                ManagementRow(
                    title = strings.changeUsername,
                    subtitle = state.me?.username.orEmpty(),
                    onClick = {
                        usernameDraft = state.me?.username.orEmpty()
                        showUsernameDialog = true
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ManagementRow(
                    title = strings.changeAvatar,
                    subtitle = strings.avatarHint,
                    onClick = { avatarPicker.launch("image/*") }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ManagementRow(
                    title = strings.changePassword,
                    subtitle = strings.newPassword,
                    onClick = { showPasswordDialog = true }
                )
            }
            ManagementSection {
                ManagementRow(
                    title = strings.logout,
                    leading = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { viewModel.logout(clearSavedLogin = true) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ManagementRow(
                    title = strings.requestAccountDeletion,
                    subtitle = strings.requestAccountDeletionConfirm,
                    destructive = true,
                    leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { showDeletionDialog = true }
                )
            }
            state.relationshipMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun SimpleTextInputDialog(
    title: String,
    label: String,
    initial: String,
    password: Boolean,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AnimatedDialogContainer(onDismissRequest = onDismiss) { dismiss ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(strings.cancel) }
                    TextButton(onClick = { onSave(value) }) { Text(strings.save) }
                }
            }
        }
    }
}

@Composable
private fun AvatarCropDialog(
    title: String,
    source: PendingAvatarCrop,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    val bitmap = remember(source.bytes) {
        BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
    }
    if (bitmap == null) {
        LaunchedEffect(source.fileName) { onDismiss() }
        return
    }

    val cropSize = 280.dp
    val density = LocalDensity.current
    val cropSizePx = remember(density) { with(density) { cropSize.roundToPx().toFloat() } }
    var scale by remember(source.bytes) { mutableStateOf(1f) }
    var offset by remember(source.bytes) { mutableStateOf(Offset.Zero) }

    AnimatedDialogContainer(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) { dismiss ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    strings.avatarCropHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .pointerInput(source.bytes) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 4.5f)
                                    scale = nextScale
                                    offset = clampAvatarCropOffset(bitmap, cropSizePx, nextScale, offset + pan)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(strings.cancel) }
                    TextButton(
                        onClick = {
                            cropAvatarBitmap(bitmap, scale, offset, cropSizePx)?.let(onConfirm)
                        }
                    ) { Text(strings.save) }
                }
            }
        }
    }
}

@Composable
private fun PeerManageScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    selectedUserCode: String?,
    onBack: () -> Unit,
    onOpenDirect: (Long) -> Unit,
) {
    val strings = stringsFor(state.language)
    val conversation = state.conversations.find { it.id == state.currentConversationId }
    val peer = selectedUserCode
        ?.takeIf { it.isNotBlank() }
        ?.let { requestedCode -> state.users.firstOrNull { it.userCode == requestedCode } }
        ?: state.users.firstOrNull { user ->
            conversation != null &&
                ((conversation.directUserCode.isNotBlank() && user.userCode == conversation.directUserCode) ||
                    user.username == conversation.directUsername)
        }
    val directConversation = peer?.let { peerUser ->
        state.conversations.firstOrNull { item ->
            item.kind == "direct" &&
                ((peerUser.userCode.isNotBlank() && item.directUserCode == peerUser.userCode) ||
                    item.directUsername == peerUser.username)
        }
    }
    val isCurrentDirectConversation = conversation?.kind == "direct" && directConversation?.id == conversation.id
    val peerCodes = state.deviceIdentities.filter { identity ->
        peer != null && ((peer.userCode.isNotBlank() && identity.userCode == peer.userCode) || identity.username == peer.username)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.friendManage, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManagementSection {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AvatarBubble(peer?.avatarUrl.orEmpty(), peer?.username.orEmpty(), peer?.color ?: "#128c7e", 76.dp, state.serverUrl)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(peer?.username.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                        peer?.userCode?.takeIf { it.isNotBlank() }?.let {
                            Text("${strings.userId}: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (peerCodes.isNotEmpty()) {
                ManagementSection(title = strings.security) {
                    peerCodes.forEachIndexed { index, identity ->
                        ManagementRow(
                            title = strings.peerSafetyCode,
                            subtitle = "${identity.safetyCode}\n${strings.deviceName}: ${identity.deviceName}"
                        )
                        if (index != peerCodes.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    }
                }
            }
            ManagementSection {
                if (isCurrentDirectConversation) {
                    ManagementRow(
                        title = strings.clearChatHistory,
                        leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            viewModel.clearCurrentConversationHistory()
                            onBack()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ManagementRow(
                        title = strings.deleteContact,
                        destructive = true,
                        leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            viewModel.deleteCurrentDirectConversation()
                            onBack()
                        }
                    )
                } else if (peer != null) {
                    ManagementRow(
                        title = if (directConversation != null) strings.privateMessage else strings.requestContact,
                        subtitle = peer.username,
                        leading = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { directConversation?.let { onOpenDirect(it.id) } ?: viewModel.requestContact(peer.userCode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupManageScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val manage = state.currentConversationManage
    val isOwner = manage?.ownRole == "owner"
    var titleDraft by rememberSaveable(manage?.id, manage?.title) { mutableStateOf(manage?.title.orEmpty()) }
    var addMemberExpanded by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var adminExpanded by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var pendingRemoveMemberCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveMemberName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveAdminCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveAdminName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingTransferOwnerCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingTransferOwnerName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var showLeaveGroupDialog by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var showDeleteGroupDialog by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var pendingAvatarCrop by remember { mutableStateOf<PendingAvatarCrop?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "group_avatar.jpg"
        pendingAvatarCrop = PendingAvatarCrop(
            fileName = name,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            bytes = bytes
        )
    }
    LaunchedEffect(state.currentConversationId) {
        viewModel.refreshCurrentConversationManage()
    }
    pendingAvatarCrop?.let { crop ->
        AvatarCropDialog(
            title = strings.changeGroupIcon,
            source = crop,
            strings = strings,
            onDismiss = { pendingAvatarCrop = null },
            onConfirm = { croppedBytes ->
                viewModel.uploadCurrentGroupAvatar("group_avatar.jpg", "image/jpeg", croppedBytes)
                pendingAvatarCrop = null
            }
        )
    }
    if (pendingRemoveMemberCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.removeMember,
            body = { Text(strings.removeMemberConfirm.format(pendingRemoveMemberName)) },
            confirmText = strings.removeMember,
            onConfirm = {
                viewModel.removeCurrentGroupMember(pendingRemoveMemberCode)
                pendingRemoveMemberCode = ""
                pendingRemoveMemberName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingRemoveMemberCode = ""
                pendingRemoveMemberName = ""
            }
        )
    }
    if (pendingRemoveAdminCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.removeAdmin,
            body = { Text(strings.removeAdminConfirm.format(pendingRemoveAdminName)) },
            confirmText = strings.removeAdmin,
            onConfirm = {
                viewModel.removeCurrentGroupAdmin(pendingRemoveAdminCode)
                pendingRemoveAdminCode = ""
                pendingRemoveAdminName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingRemoveAdminCode = ""
                pendingRemoveAdminName = ""
            }
        )
    }
    if (pendingTransferOwnerCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.transferOwner,
            body = { Text(strings.transferOwnerConfirm.format(pendingTransferOwnerName)) },
            confirmText = strings.transferOwner,
            onConfirm = {
                viewModel.transferCurrentGroupOwner(pendingTransferOwnerCode)
                pendingTransferOwnerCode = ""
                pendingTransferOwnerName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingTransferOwnerCode = ""
                pendingTransferOwnerName = ""
            }
        )
    }
    if (showLeaveGroupDialog) {
        AnimatedActionDialog(
            title = strings.leaveGroup,
            body = { Text(strings.leaveGroupConfirm) },
            confirmText = strings.leaveGroup,
            onConfirm = {
                showLeaveGroupDialog = false
                viewModel.leaveCurrentGroupConversation()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showLeaveGroupDialog = false }
        )
    }
    if (showDeleteGroupDialog) {
        AnimatedActionDialog(
            title = strings.deleteGroup,
            body = { Text(strings.deleteGroupConfirm) },
            confirmText = strings.deleteGroup,
            onConfirm = {
                showDeleteGroupDialog = false
                viewModel.deleteCurrentGroupConversation()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showDeleteGroupDialog = false }
        )
    }
    val memberCodes = remember(manage?.members) {
        manage?.members?.mapNotNull { it.user.userCode.takeIf(String::isNotBlank) }?.toSet().orEmpty()
    }
    val directContactKeys = remember(state.conversations) {
        state.conversations
            .filter { it.kind == "direct" }
            .flatMap { listOf(it.directUserCode, it.directUsername) }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val addableContacts = remember(state.users, directContactKeys, memberCodes, state.me?.userCode) {
        state.users
            .filter { user ->
                user.userCode.isNotBlank() &&
                    user.userCode != state.me?.userCode &&
                    user.userCode !in memberCodes &&
                    (user.userCode in directContactKeys || user.username in directContactKeys)
            }
            .distinctBy { it.userCode }
            .sortedBy { it.username.lowercase() }
    }
    val adminCandidates = remember(manage?.members, state.me?.userCode) {
        manage?.members
            ?.filter { member ->
                member.role == "member" &&
                    member.user.userCode.isNotBlank() &&
                    member.user.userCode != state.me?.userCode
            }
            ?.sortedBy { it.user.username.lowercase() }
            .orEmpty()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.groupManage, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (manage == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ManagementSection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val groupAvatarShape = RoundedCornerShape(20.dp)
                        Box(modifier = Modifier.clip(groupAvatarShape).clickable(enabled = manage.canManage) { avatarPicker.launch("image/*") }) {
                            AvatarBubble(manage.avatarUrl, manage.title, "#128c7e", 76.dp, state.serverUrl, shape = groupAvatarShape)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(manage.title, style = MaterialTheme.typography.headlineSmall)
                            Text("${strings.groupId}: ${manage.groupCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text("${strings.adminLimit}: ${manage.adminCount}/${manage.adminLimit}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text("${strings.groupKeyEpoch}: ${manage.keyEpoch}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${strings.groupKeyDevicesReady}: ${manage.keyReadyDeviceCount}/${manage.keyDeviceCount}",
                                color = if (manage.keyReadyDeviceCount >= manage.keyDeviceCount) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (manage.canManage) {
                    ManagementSection(title = strings.groupManage) {
                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            label = { Text(strings.groupName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { viewModel.changeCurrentGroupTitle(titleDraft) }, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.save)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                        ManagementRow(
                            title = strings.changeGroupIcon,
                            subtitle = strings.avatarHint,
                            leading = { Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { avatarPicker.launch("image/*") }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        Column(modifier = Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings.messageExpiration, style = MaterialTheme.typography.bodyLarge)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    0L to strings.noExpiration,
                                    8L * 60L * 60L * 1000L to strings.hours8,
                                    24L * 60L * 60L * 1000L to strings.hours24,
                                    72L * 60L * 60L * 1000L to strings.hours72,
                                ).forEach { (ttl, label) ->
                                    FilterChip(
                                        selected = manage.messageTtlMs == ttl,
                                        onClick = { viewModel.setCurrentGroupExpiration(ttl) },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        ManagementRow(
                            title = strings.clearChatHistory,
                            leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { viewModel.clearCurrentConversationHistory() }
                        )
                    }

                    ManagementSection {
                        ExpandableHeader(
                            title = strings.addGroupMember,
                            subtitle = strings.selectContact,
                            expanded = addMemberExpanded,
                            onClick = { addMemberExpanded = !addMemberExpanded }
                        )
                        AnimatedVisibility(visible = addMemberExpanded) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                if (addableContacts.isEmpty()) {
                                    Text(strings.noAvailableContacts, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                } else {
                                    addableContacts.forEachIndexed { index, user ->
                                        ManagementRow(
                                            title = user.username,
                                            subtitle = "${strings.userId}: ${user.userCode}",
                                            leading = { AvatarBubble(user.avatarUrl, user.username, user.color, 36.dp, state.serverUrl) },
                                            trailing = {
                                                TextButton(onClick = { viewModel.addCurrentGroupMember(user.userCode) }) {
                                                    Text(strings.addGroupMember)
                                                }
                                            }
                                        )
                                        if (index != addableContacts.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }

                    ManagementSection {
                        ExpandableHeader(
                            title = if (manage.canManageOwner) strings.addAdmin else strings.requestAdmin,
                            subtitle = strings.selectMember,
                            expanded = adminExpanded,
                            onClick = { adminExpanded = !adminExpanded }
                        )
                        AnimatedVisibility(visible = adminExpanded) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                if (adminCandidates.isEmpty()) {
                                    Text(strings.noAvailableMembers, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                } else {
                                    adminCandidates.forEachIndexed { index, member ->
                                        ManagementRow(
                                            title = member.user.username,
                                            subtitle = "${strings.userId}: ${member.user.userCode}",
                                            leading = { AvatarBubble(member.user.avatarUrl, member.user.username, member.user.color, 36.dp, state.serverUrl) },
                                            trailing = {
                                                TextButton(onClick = { viewModel.requestCurrentGroupAdmin(member.user.userCode) }) {
                                                    Text(if (manage.canManageOwner) strings.addAdmin else strings.requestAdmin)
                                                }
                                            }
                                        )
                                        if (index != adminCandidates.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }

                    if (manage.pendingJoinRequests.isNotEmpty() || manage.pendingAdminRequests.isNotEmpty()) {
                        ManagementSection(title = strings.incomingRequests) {
                            manage.pendingJoinRequests.forEachIndexed { index, request ->
                                ManagementRow(
                                    title = request.requester.username,
                                    subtitle = "${strings.groupCode}: ${request.groupCode}",
                                    leading = { AvatarBubble(request.requester.avatarUrl, request.requester.username, request.requester.color, 36.dp, state.serverUrl) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { viewModel.reviewGroupJoinRequest(request.id, true) }) { Text(strings.approve) }
                                            TextButton(onClick = { viewModel.reviewGroupJoinRequest(request.id, false) }) { Text(strings.reject) }
                                        }
                                    }
                                )
                                if (index != manage.pendingJoinRequests.lastIndex || manage.pendingAdminRequests.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                            }
                            manage.pendingAdminRequests.forEachIndexed { index, request ->
                                ManagementRow(
                                    title = "${request.requesterUsername} -> ${request.targetUsername}",
                                    subtitle = strings.addAdmin,
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { viewModel.reviewGroupAdminRequest(request.id, true) }) { Text(strings.approve) }
                                            TextButton(onClick = { viewModel.reviewGroupAdminRequest(request.id, false) }) { Text(strings.reject) }
                                        }
                                    }
                                )
                                if (index != manage.pendingAdminRequests.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }

                ManagementSection(title = strings.member) {
                    manage.members.forEachIndexed { index, member ->
                        val roleLabel = when (member.role) {
                            "owner" -> strings.owner
                            "admin" -> strings.admin
                            else -> strings.member
                        }
                        val canRemoveMember = manage.canManage &&
                            member.role != "owner" &&
                            member.user.userCode != state.me?.userCode &&
                            (isOwner || member.role != "admin")
                        val canRemoveAdmin = isOwner &&
                            member.role == "admin" &&
                            member.user.userCode != state.me?.userCode
                        val canTransferOwner = isOwner && member.user.userCode != state.me?.userCode
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = member.user.userCode.isNotBlank()) {
                                        onOpenMember(member.user.userCode)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AvatarBubble(
                                    member.user.avatarUrl,
                                    member.user.username,
                                    member.user.color,
                                    38.dp,
                                    state.serverUrl
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(member.user.username, style = MaterialTheme.typography.bodyLarge)
                                    Text(roleLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (canRemoveMember || canRemoveAdmin || canTransferOwner) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 50.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (canRemoveAdmin) {
                                        TextButton(onClick = {
                                            pendingRemoveAdminCode = member.user.userCode
                                            pendingRemoveAdminName = member.user.username
                                        }) {
                                            Text(strings.removeAdmin)
                                        }
                                    }
                                    if (canRemoveMember) {
                                        TextButton(onClick = {
                                            pendingRemoveMemberCode = member.user.userCode
                                            pendingRemoveMemberName = member.user.username
                                        }) {
                                            Text(strings.removeMember)
                                        }
                                    }
                                    if (canTransferOwner) {
                                        TextButton(onClick = {
                                            pendingTransferOwnerCode = member.user.userCode
                                            pendingTransferOwnerName = member.user.username
                                        }) {
                                            Text(strings.transferOwner)
                                        }
                                    }
                                }
                            }
                        }
                        if (index != manage.members.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }

                ManagementSection {
                    if (isOwner) {
                        ManagementRow(
                            title = strings.deleteGroup,
                            subtitle = strings.deleteGroupConfirm,
                            destructive = true,
                            leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showDeleteGroupDialog = true }
                        )
                    } else {
                        ManagementRow(
                            title = strings.leaveGroup,
                            subtitle = strings.leaveGroupConfirm,
                            destructive = true,
                            leading = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showLeaveGroupDialog = true }
                        )
                    }
                }

                state.relationshipMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenPrerelease: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showAccountDeletionDialog by remember { mutableStateOf(false) }
    var passwordDraft by rememberSaveable { mutableStateOf("") }
    var usernameDraft by rememberSaveable(state.me?.username) { mutableStateOf(state.me?.username.orEmpty()) }
    val currentVersion = remember(context) { currentAppVersion(context) }
    val release = state.latestAppRelease
    val updateAvailable = release != null && compareVersionNames(release.version, currentVersion) > 0
    val updateProgress = state.downloadProgress
    val downloadedUpdate = state.downloadedUpdate
    val releaseFileName = releaseDownloadFileName(release)
    val updateAlreadyDownloaded = downloadedUpdate != null && downloadedUpdate.name == releaseFileName
    val avatarPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "avatar.jpg"
        viewModel.uploadAvatar(name, context.contentResolver.getType(uri) ?: "image/jpeg", bytes)
    }

    if (showPasswordDialog) {
        AnimatedDialogContainer(onDismissRequest = { showPasswordDialog = false }) { dismiss ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(strings.changePassword, style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = passwordDraft,
                        onValueChange = { passwordDraft = it },
                        label = { Text(strings.newPassword) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = dismiss) { Text(strings.cancel) }
                        TextButton(onClick = {
                            dismiss()
                            viewModel.changePassword(passwordDraft)
                            passwordDraft = ""
                        }) {
                            Text(strings.save)
                        }
                    }
                }
            }
        }
    }

    if (showUsernameDialog) {
        AnimatedDialogContainer(onDismissRequest = { showUsernameDialog = false }) { dismiss ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text(strings.changeUsername, style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = usernameDraft,
                        onValueChange = { usernameDraft = it },
                        label = { Text(strings.newUsername) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = dismiss) { Text(strings.cancel) }
                        TextButton(onClick = {
                            dismiss()
                            viewModel.changeUsername(usernameDraft)
                        }) {
                            Text(strings.save)
                        }
                    }
                }
            }
        }
    }

    if (showAccountDeletionDialog) {
        AnimatedActionDialog(
            title = strings.requestAccountDeletion,
            body = { Text(strings.requestAccountDeletionConfirm) },
            confirmText = strings.requestAccountDeletion,
            onConfirm = {
                showAccountDeletionDialog = false
                viewModel.requestAccountDeletion()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showAccountDeletionDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.settings, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarBubble(
                            state.me?.avatarUrl.orEmpty(),
                            state.me?.username.orEmpty(),
                            state.me?.color ?: "#128c7e",
                            64.dp,
                            serverUrl = state.serverUrl
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.me?.username.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            state.me?.userCode?.takeIf { it.isNotBlank() }?.let {
                                Text("${strings.userId}: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Text(strings.avatarHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                usernameDraft = state.me?.username.orEmpty()
                                showUsernameDialog = true
                            }) {
                                Text(strings.changeUsername)
                            }
                            FilledTonalButton(onClick = { avatarPicker.launch("image/*") }) {
                                Text(strings.changeAvatar)
                            }
                        }
                    }
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings.deviceSecurity, style = MaterialTheme.typography.titleSmall)
                            val identity = state.deviceIdentity
                            if (identity == null) {
                                Text(strings.noDeviceIdentity, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            } else {
                                Text("${strings.deviceName}: ${identity.deviceName}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Text("${strings.deviceId}: ${identity.deviceId}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Text(strings.safetyCode, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    identity.safetyCode,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(strings.safetyCodeHint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = state.e2eeEnabled, onCheckedChange = viewModel::updateE2eeEnabled)
                        Text(strings.e2ee)
                    }
                    LanguageSelector(state.language, viewModel::updateLanguage, strings.language)
                    DisplayModeSelector(
                        mode = state.displayMode,
                        onSelect = viewModel::updateDisplayMode,
                        strings = strings
                    )
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(strings.systemColors, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    strings.systemColorsHint,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = state.dynamicColorsEnabled,
                                onCheckedChange = viewModel::updateDynamicColorsEnabled
                            )
                        }
                    }
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${strings.currentVersion}: $currentVersion", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${strings.latestVersion}: ${releaseDisplayVersion(release)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                state.updateStatus
                                    ?: if (release == null) strings.noReleaseUploaded
                                    else if (updateAvailable) "${strings.updateAvailable}: ${releaseDisplayVersion(release)}"
                                    else strings.latestVersionInstalled,
                                color = if (updateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            updateProgress?.let { progress ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${strings.downloading}: ${progress.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
                                    Text("${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                            if (downloadedUpdate != null) {
                                Text(
                                    "${strings.updateDownloaded}: ${downloadedUpdate.name}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(
                                    onClick = viewModel::checkForUpdates,
                                    enabled = !state.isCheckingUpdate
                                ) {
                                    Text(if (state.isCheckingUpdate) strings.checkingUpdate else strings.checkUpdate)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch { downloadLatestUpdate(context, viewModel) }
                                    },
                                    enabled = updateAvailable && !updateAlreadyDownloaded && !state.isCheckingUpdate && updateProgress == null
                                ) {
                                    Text(strings.downloadUpdate)
                                }
                            }
                            if (downloadedUpdate != null) {
                                FilledTonalButton(
                                    onClick = { openInstaller(context, viewModel, downloadedUpdate) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.openInstaller)
                                }
                            }
                        }
                    }
                    FilledTonalButton(onClick = onOpenPrerelease, modifier = Modifier.fillMaxWidth()) { Text(strings.prereleaseBuilds) }
                    FilledTonalButton(onClick = { showPasswordDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(strings.changePassword) }
                    FilledTonalButton(
                        onClick = { showAccountDeletionDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.requestAccountDeletion)
                    }
                    FilledTonalButton(onClick = { viewModel.logout(clearSavedLogin = true) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.logout)
                    }
                    if (state.me?.isAdmin == true) {
                        FilledTonalButton(onClick = viewModel::deleteHistory, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.deleteHistory)
                        }
                    }
                }
            }
            state.relationshipMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
        }
    }
}

@Composable
private fun PrereleaseScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember(context) { currentAppVersion(context) }
    val release = state.latestPrerelease
    val updateProgress = state.downloadProgress
    val downloadedUpdate = state.downloadedPrerelease
    val releaseFileName = releaseDownloadFileName(release)
    val updateAlreadyDownloaded = downloadedUpdate != null && downloadedUpdate.name == releaseFileName

    LaunchedEffect(Unit) {
        viewModel.checkForPrerelease()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.prereleaseCenter, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${strings.currentVersion}: $currentVersion", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${strings.latestVersion}: ${releaseDisplayVersion(release)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        state.prereleaseStatus
                            ?: if (release == null) strings.noPrereleaseUploaded
                            else "${strings.prereleaseAvailable}: ${releaseDisplayVersion(release)}",
                        color = if (release != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    updateProgress?.let { progress ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${strings.downloading}: ${progress.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
                            Text("${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    if (downloadedUpdate != null) {
                        Text(
                            "${strings.updateDownloaded}: ${downloadedUpdate.name}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = viewModel::checkForPrerelease,
                            enabled = !state.isCheckingPrerelease
                        ) {
                            Text(if (state.isCheckingPrerelease) strings.checkingUpdate else strings.checkUpdate)
                        }
                        FilledTonalButton(
                            onClick = {
                                scope.launch { downloadLatestPrerelease(context, viewModel) }
                            },
                            enabled = release != null && release.version.isNotBlank() && !updateAlreadyDownloaded && !state.isCheckingPrerelease && updateProgress == null
                        ) {
                            Text(strings.downloadUpdate)
                        }
                    }
                    if (downloadedUpdate != null) {
                        FilledTonalButton(
                            onClick = { openInstaller(context, viewModel, downloadedUpdate) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.openInstaller)
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
    onStartVoiceCall: (Long) -> Unit,
    onOpenPeerManage: (String?) -> Unit,
    onOpenGroupManage: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val localPrefs = remember(context) { ChatPreferences(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shouldAutoScroll = remember { mutableStateOf(true) }
    val conversation = state.conversations.find { it.id == state.currentConversationId } ?: return
    val bottomBounceOffset = remember(conversation.id) { Animatable(0f) }
    val decryptSecret = ""
    val decryptedTextCache = remember(decryptSecret, conversation.id) { mutableStateMapOf<Long, String>() }
    val attachmentMetaCache = remember(decryptSecret, conversation.id) { mutableStateMapOf<Long, JSONObject?>() }
    val messagesById = remember(state.messages) { state.messages.associateBy { it.id } }
    val usersByIdentity = remember(state.users) {
        buildMap {
            state.users.forEach { user ->
                if (user.userCode.isNotBlank()) put(user.userCode, user)
                put(user.username, user)
            }
        }
    }
    val directPeer = remember(conversation.directUserCode, conversation.directUsername, state.users) {
        state.users.firstOrNull { user ->
            (conversation.directUserCode.isNotBlank() && user.userCode == conversation.directUserCode) ||
                user.username == conversation.directUsername
        }
    }
    val bubbleMaxWidth = remember(configuration.screenWidthDp) { configuration.screenWidthDp.dp * 0.84f }
    LaunchedEffect(conversation.id, conversation.kind) {
        if (conversation.kind == "group") viewModel.refreshCurrentConversationManage()
    }
    val groupMembers = remember(conversation.id, state.currentConversationManage) {
        state.currentConversationManage
            ?.takeIf { it.id == conversation.id && it.kind == "group" }
            ?.members
            .orEmpty()
    }
    val mentionCandidates = remember(conversation.id, conversation.kind, conversation.directUserCode, conversation.directUsername, state.users, groupMembers, state.me?.username, state.me?.userCode) {
        if (conversation.kind == "direct") {
            state.users
                .filter { (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) || it.username == conversation.directUsername }
                .filter { it.userCode != state.me?.userCode && it.username != state.me?.username }
        } else {
            groupMembers
                .map { it.user }
                .filter { it.userCode != state.me?.userCode && it.username != state.me?.username }
        }
    }
    val participantUsernames = remember(mentionCandidates) {
        mentionCandidates
            .map { it.userCode.ifBlank { it.username } }
            .filter { it.isNotBlank() }
    }
    var replyTarget by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    val activeReplyTarget = remember(replyTarget, messagesById) { replyTarget?.let { messagesById[it.id] ?: it } }
    val activeReplyPreview = remember(
        activeReplyTarget?.id,
        activeReplyTarget?.kind,
        activeReplyTarget?.payload,
        activeReplyTarget?.replyTo?.preview,
        decryptSecret
    ) {
        activeReplyTarget?.let(viewModel::buildReplyPreview)
    }
    var composer by rememberSaveable(conversation.id, stateSaver = TextFieldValue.Saver) {
        val draft = localPrefs.draftText(conversation.id)
        mutableStateOf(TextFieldValue(draft, selection = TextRange(draft.length)))
    }
    LaunchedEffect(conversation.id) {
        val draft = localPrefs.draftText(conversation.id)
        composer = TextFieldValue(draft, selection = TextRange(draft.length))
    }
    var attachmentMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var voiceInputMode by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var cameraVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var previewImage by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    var retryTarget by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    var audioPlayer by remember(conversation.id) { mutableStateOf<MediaPlayer?>(null) }
    var playingAudioMessageId by remember(conversation.id) { mutableStateOf<Long?>(null) }
    var voiceGestureCancelled by remember(conversation.id) { mutableStateOf(false) }
    var showClearHistoryDialog by remember(conversation.id) { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    val currentUserIdentity = remember(state.me?.userCode, state.me?.username) {
        state.me?.userCode?.ifBlank { state.me?.username.orEmpty() }
            ?.ifBlank { state.me?.username.orEmpty() }
            .orEmpty()
    }
    val heardAudioMessages = remember(conversation.id, currentUserIdentity) { mutableStateMapOf<Long, Boolean>() }
    val chatBounceConnection = rememberBottomBounceConnection(listState, bottomBounceOffset)
    val connectionStatusColor = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) {}
    val recordPermission = rememberLauncherForActivityResult(RequestPermission()) { }
    val cameraPermission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            cameraVisible = true
        } else {
            viewModel.reportError(strings.cameraPermissionDenied)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "image.jpg"
        val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
        shouldAutoScroll.value = true
        viewModel.uploadAttachmentFromUri("image", name, context.contentResolver.getType(uri) ?: "image/jpeg", uri, reply)
        replyTarget = null
    }
    val filePicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "file.bin"
        val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
        shouldAutoScroll.value = true
        viewModel.uploadAttachmentFromUri("file", name, context.contentResolver.getType(uri) ?: "application/octet-stream", uri, reply)
        replyTarget = null
    }

    if (Build.VERSION.SDK_INT >= 33) {
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(lifecycleOwner, conversation.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(conversation.id) {
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            recordingStartedAt = 0L
            voiceGestureCancelled = false
            audioPlayer?.release()
            audioPlayer = null
            playingAudioMessageId = null
        }
    }

    fun startVoiceRecordingIfPossible() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording(context) { media, file, started ->
            recorder = media
            recordingFile = file
            recordingStartedAt = started
            voiceGestureCancelled = false
        }
    }

    fun finishVoiceRecording(send: Boolean) {
        val media = recorder ?: return
        val file = recordingFile
        runCatching { media.stop() }
        media.release()
        recorder = null
        recordingFile = null
        val durationMs = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
        recordingStartedAt = 0L
        val shouldSend = send && !voiceGestureCancelled
        voiceGestureCancelled = false
        if (shouldSend && file != null && file.exists() && durationMs >= 250L) {
            val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
            shouldAutoScroll.value = true
            viewModel.uploadAttachment("audio", file.name, "audio/mp4", file.readBytes(), reply, durationMs)
            replyTarget = null
        }
        file?.delete()
    }

    fun stopAudioPlayback() {
        audioPlayer?.runCatching {
            if (isPlaying) stop()
        }
        audioPlayer?.release()
        audioPlayer = null
        playingAudioMessageId = null
    }

    fun belongsToCurrentUser(message: ChatMessage): Boolean = when {
        message.userCode.isNotBlank() && state.me?.userCode?.isNotBlank() == true -> message.userCode == state.me?.userCode
        else -> message.username == state.me?.username
    }

    suspend fun toggleAudioPlayback(message: ChatMessage) {
        if (playingAudioMessageId == message.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        runCatching {
            val attachment = viewModel.materializeAttachment(context, message, exportToDownloads = false)
            val file = attachment.file ?: throw IllegalStateException("Unable to access voice note")
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (playingAudioMessageId == message.id) {
                        audioPlayer = null
                        playingAudioMessageId = null
                    }
                }
                prepare()
                start()
            }
            if (!belongsToCurrentUser(message) && currentUserIdentity.isNotBlank()) {
                localPrefs.markAudioMessageHeard(currentUserIdentity, message.id)
                heardAudioMessages[message.id] = true
            }
            audioPlayer = player
            playingAudioMessageId = message.id
        }.onFailure {
            viewModel.reportError(it.message ?: "Unable to play voice note")
            viewModel.clearDownloadProgress()
        }
    }

    fun openInAppCamera() {
        attachmentMenuExpanded = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraVisible = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && shouldAutoScroll.value) {
            listState.scrollToItem(state.messages.lastIndex)
            viewModel.markReadLatest()
        }
    }

    LaunchedEffect(state.messages.map { it.id to it.kind }, currentUserIdentity) {
        if (currentUserIdentity.isBlank()) {
            heardAudioMessages.clear()
        } else {
            val activeAudioIds = state.messages.asSequence()
                .filter { it.kind == "audio" }
                .map { it.id }
                .toSet()
            heardAudioMessages.keys.toList().forEach { id ->
                if (id !in activeAudioIds) heardAudioMessages.remove(id)
            }
            state.messages.asSequence()
                .filter { it.kind == "audio" }
                .forEach { message ->
                    if (message.id !in heardAudioMessages) {
                        heardAudioMessages[message.id] = localPrefs.hasHeardAudioMessage(currentUserIdentity, message.id)
                    }
                }
        }
    }

    LaunchedEffect(activeReplyTarget?.id, activeReplyTarget?.kind) {
        if (activeReplyTarget?.kind == "recalled" || activeReplyTarget?.kind == "attachment_cleared") {
            replyTarget = null
        }
    }

    val mentionQuery = remember(composer) { currentMentionQuery(composer) }

    if (showClearHistoryDialog) {
        AnimatedActionDialog(
            title = strings.clearChatHistory,
            body = { Text(strings.deleteHistoryConfirm) },
            confirmText = strings.delete,
            onConfirm = {
                showClearHistoryDialog = false
                viewModel.clearCurrentConversationHistory()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showClearHistoryDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val avatarShape = if (conversation.kind == "group") RoundedCornerShape(10.dp) else CircleShape
            AppTopBar(
                title = conversation.title,
                subtitle = if (state.isConnected) strings.connected else strings.disconnected,
                subtitleColor = connectionStatusColor,
                onBack = onBack,
                navigationAvatar = {
                    Box(
                        modifier = Modifier
                            .clip(avatarShape)
                            .clickable {
                                if (conversation.kind == "group") {
                                    onOpenGroupManage()
                                } else {
                                    onOpenPeerManage(directPeer?.userCode)
                                }
                            }
                    ) {
                        AvatarBubble(
                            conversation.avatarUrl.ifBlank { directPeer?.avatarUrl.orEmpty() },
                            conversation.title,
                            directPeer?.color ?: "#128c7e",
                            36.dp,
                            serverUrl = state.serverUrl,
                            shape = avatarShape
                        )
                    }
                },
                actions = {
                    if (conversation.kind == "direct") {
                        IconButton(
                            onClick = { onStartVoiceCall(conversation.id) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = strings.voiceCall)
                        }
                        IconButton(
                            onClick = { showClearHistoryDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = strings.clearChatHistory)
                        }
                    }
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                    }
                    IconButton(
                        onClick = {
                            if (state.messages.isNotEmpty()) {
                                scope.launch { listState.animateScrollToItem(state.messages.lastIndex) }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                    }
                    IconButton(onClick = { viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refresh)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)
        ) {
            state.uploadProgress?.let { TransferProgressCard(strings.uploading, it) }
            state.downloadProgress?.let { TransferProgressCard(strings.downloading, it) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .nestedScroll(chatBounceConnection)
                    .offset { IntOffset(0, bottomBounceOffset.value.roundToInt()) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val previousMessage = state.messages.getOrNull(index - 1)
                        if (previousMessage != null && shouldShowTimelineSeparator(previousMessage.ts, message.ts)) {
                            TimelineSeparator(label = formatTimelineLabel(message.ts, state.language, strings))
                        }
                        MessageBubble(
                            message = message,
                            state = state,
                            conversationKind = conversation.kind,
                            sender = usersByIdentity[message.userCode.ifBlank { message.username }],
                            strings = strings,
                            participantUsernames = participantUsernames,
                            bubbleMaxWidth = bubbleMaxWidth,
                            messagesById = messagesById,
                            decryptedTextCache = decryptedTextCache,
                            attachmentMetaCache = attachmentMetaCache,
                            localPrefs = localPrefs,
                            onReply = { replyTarget = it },
                            onRecall = { viewModel.recallMessage(it) },
                            onRetryTap = { retryTarget = it },
                            onOpenAttachment = { opened -> scope.launch { openAttachment(context, viewModel, opened) } },
                            onPreviewImage = { previewImage = it },
                            onPlayAudio = { opened -> scope.launch { toggleAudioPlayback(opened) } },
                            onSenderAvatarClick = { userCode -> onOpenPeerManage(userCode) },
                            isAudioPlaying = playingAudioMessageId == message.id,
                            hasUnreadAudioIndicator = message.kind == "audio" &&
                                !belongsToCurrentUser(message) &&
                                heardAudioMessages[message.id] != true
                        )
                    }
                }
            }

            LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                shouldAutoScroll.value = lastVisible >= (state.messages.lastIndex - 2).coerceAtLeast(0)
            }

            previewImage?.let { imageMessage ->
                ImagePreviewDialog(
                    message = imageMessage,
                    localPrefs = localPrefs,
                    strings = strings,
                    serverUrl = state.serverUrl,
                    onDismiss = { previewImage = null },
                    onDownload = {
                        scope.launch {
                            downloadAndOpenAttachment(context, viewModel, imageMessage)
                        }
                    }
                )
            }

            retryTarget?.let { failedMessage ->
                AnimatedActionDialog(
                    title = strings.retrySend,
                    body = { Text(strings.retrySendConfirm) },
                    confirmText = strings.retrySend,
                    onConfirm = {
                        shouldAutoScroll.value = true
                        viewModel.retryMessage(failedMessage.id)
                        retryTarget = null
                    },
                    dismissText = strings.cancel,
                    onDismissRequest = { retryTarget = null }
                )
            }

            if (cameraVisible) {
                InAppCameraDialog(
                    strings = strings,
                    onDismiss = { cameraVisible = false },
                    onError = { viewModel.reportError(it) },
                    onSendPhoto = { file ->
                        scope.launch {
                            runCatching {
                                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                                val reply = activeReplyTarget?.let {
                                    ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty())
                                }
                                val fileName = "photo_${System.currentTimeMillis()}.jpg"
                                shouldAutoScroll.value = true
                                viewModel.uploadAttachment("image", fileName, "image/jpeg", bytes, reply)
                                replyTarget = null
                            }.onFailure {
                                viewModel.reportError(it.message ?: strings.cameraCaptureFailed)
                            }
                            file.delete()
                            cameraVisible = false
                        }
                    }
                )
            }

            activeReplyTarget?.let { target ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${strings.replyingTo} ${target.username}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            ReplyPreviewText(
                                fallbackPreview = activeReplyPreview.orEmpty(),
                                originalMessage = target,
                                secret = decryptSecret,
                                strings = strings,
                                decryptedTextCache = decryptedTextCache,
                                attachmentMetaCache = attachmentMetaCache,
                                localPrefs = localPrefs
                            )
                        }
                        TextButton(onClick = { replyTarget = null }) { Text(strings.cancel) }
                    }
                }
            }

            if (mentionQuery != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mentionCandidates.filter { it.username.contains(mentionQuery, true) }.take(6).forEach { user ->
                        FilledTonalButton(onClick = {
                            val updated = replaceMention(composer, user.username)
                            composer = updated
                            localPrefs.putDraftText(conversation.id, updated.text)
                        }) {
                            Text("@${user.username}")
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    if (recorder != null) {
                        Text(
                            strings.recording,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            IconButton(
                                onClick = { attachmentMenuExpanded = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Add, null, tint = if (recorder == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                            DropdownMenu(expanded = attachmentMenuExpanded, onDismissRequest = { attachmentMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(strings.takePhoto) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = { openInAppCamera() }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.image) },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { attachmentMenuExpanded = false; imagePicker.launch("image/*") }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.file) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                                    onClick = { attachmentMenuExpanded = false; filePicker.launch("*/*") }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.voiceNote) },
                                    leadingIcon = { Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = {
                                        attachmentMenuExpanded = false
                                        voiceInputMode = true
                                    }
                                )
                            }
                        }
                        if (voiceInputMode) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (recorder != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .pointerInteropFilter { event ->
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                voiceGestureCancelled = false
                                                startVoiceRecordingIfPossible()
                                                true
                                            }

                                            MotionEvent.ACTION_MOVE -> {
                                                voiceGestureCancelled = event.y < -32f
                                                true
                                            }

                                            MotionEvent.ACTION_UP -> {
                                                finishVoiceRecording(send = true)
                                                true
                                            }

                                            MotionEvent.ACTION_CANCEL -> {
                                                voiceGestureCancelled = true
                                                finishVoiceRecording(send = false)
                                                true
                                            }

                                            else -> false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when {
                                        recorder == null -> strings.holdToTalk
                                        voiceGestureCancelled -> strings.releaseToCancel
                                        else -> strings.releaseToSend
                                    },
                                    color = if (recorder != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    voiceInputMode = false
                                    finishVoiceRecording(send = false)
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.Keyboard, strings.keyboardInput, tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            TextField(
                                value = composer,
                                onValueChange = {
                                    composer = it
                                    localPrefs.putDraftText(conversation.id, it.text)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp),
                                placeholder = { Text(strings.typeMessage) },
                                maxLines = 5,
                                shape = RoundedCornerShape(18.dp)
                            )
                            IconButton(
                                onClick = {
                                    val outgoingText = composer.text
                                    if (outgoingText.isBlank()) return@IconButton
                                    shouldAutoScroll.value = true
                                    val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
                                    viewModel.sendText(outgoingText, reply)
                                    composer = TextFieldValue("")
                                    localPrefs.putDraftText(conversation.id, "")
                                    replyTarget = null
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferProgressCard(label: String, progress: TransferProgress) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$label: ${progress.label}", fontSize = 13.sp)
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
            Text("${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InAppCameraDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onSendPhoto: (File) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewUseCase = remember { CameraPreview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var captureInProgress by remember { mutableStateOf(false) }
    var captureRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    val cameraPreviewTargetPx = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            maxOf(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp).roundToPx()
        }
    }
    val capturedBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, capturedFile?.absolutePath, cameraPreviewTargetPx) {
        value = capturedFile
            ?.takeIf { it.exists() }
            ?.let { file ->
                withContext(Dispatchers.IO) {
                    decodeScaledBitmap(file, cameraPreviewTargetPx)?.asImageBitmap()
                }
            }
    }

    fun discardPhotoAndClose() {
        capturedFile?.takeIf { it.exists() }?.delete()
        capturedFile = null
        captureInProgress = false
        runCatching { cameraProvider?.unbindAll() }
        onDismiss()
    }

    DisposableEffect(previewView, lifecycleOwner, capturedFile) {
        val currentPreview = previewView
        if (currentPreview == null || capturedFile != null) {
            onDispose { }
        } else {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                runCatching {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    provider.unbindAll()
                    previewUseCase.setSurfaceProvider(currentPreview.surfaceProvider)
                    captureRotation = currentPreview.display?.rotation ?: captureRotation
                    imageCapture.targetRotation = captureRotation
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        imageCapture,
                    )
                }.onFailure {
                    onError(it.message ?: strings.cameraUnavailable)
                    discardPhotoAndClose()
                }
            }
            providerFuture.addListener(listener, mainExecutor)
            onDispose {
                runCatching { cameraProvider?.unbindAll() }
            }
        }
    }

    DisposableEffect(context) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val nextRotation = rotationFromOrientation(orientation) ?: return
                captureRotation = nextRotation
                imageCapture.targetRotation = nextRotation
            }
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        onDispose {
            orientationListener.disable()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            capturedFile?.takeIf { it.exists() }?.delete()
        }
    }

    Dialog(
        onDismissRequest = { discardPhotoAndClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        ConfigureDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (capturedFile == null) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { discardPhotoAndClose() }) {
                        Text(strings.cancel, color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(strings.takePhoto, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(64.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 78.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (captureInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
                    } else {
                        IconButton(
                            onClick = {
                                val outputFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                                imageCapture.targetRotation = captureRotation
                                captureInProgress = true
                                imageCapture.takePicture(
                                    ImageCapture.OutputFileOptions.Builder(outputFile).build(),
                                    mainExecutor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        normalizeCapturedPhoto(outputFile, maxDimensionPx = 2560)
                                                    }
                                                }.onSuccess {
                                                    captureInProgress = false
                                                    runCatching { cameraProvider?.unbindAll() }
                                                    capturedFile?.takeIf { it.exists() && it.absolutePath != outputFile.absolutePath }?.delete()
                                                    capturedFile = outputFile
                                                }.onFailure {
                                                    captureInProgress = false
                                                    outputFile.delete()
                                                    onError(it.message ?: strings.cameraCaptureFailed)
                                                }
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            captureInProgress = false
                                            outputFile.delete()
                                            onError(exception.message ?: strings.cameraCaptureFailed)
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.size(86.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(74.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.92f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = strings.takePhoto,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                capturedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = strings.takePhoto,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { discardPhotoAndClose() }) {
                        Text(strings.cancel, color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(strings.takePhoto, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(64.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 68.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { discardPhotoAndClose() }) {
                        Text(strings.cancel, color = Color.White)
                    }
                    Button(
                        onClick = {
                            val photo = capturedFile ?: return@Button
                            onSendPhoto(photo)
                            capturedFile = null
                        },
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(strings.sendPhoto)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    state: ChatUiState,
    conversationKind: String,
    sender: ChatUser?,
    strings: AppStrings,
    participantUsernames: List<String>,
    bubbleMaxWidth: Dp,
    messagesById: Map<Long, ChatMessage>,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    onReply: (ChatMessage) -> Unit,
    onRecall: (ChatMessage) -> Unit,
    onRetryTap: (ChatMessage) -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onPreviewImage: (ChatMessage) -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onSenderAvatarClick: (String) -> Unit,
    isAudioPlaying: Boolean,
    hasUnreadAudioIndicator: Boolean,
) {
    if (message.kind == "security_notice") {
        val noticePayload = remember(message.payload) { parseJson(message.payload) }
        val noticeText = buildString {
            val noticeType = noticePayload?.optString("type").orEmpty()
            append(
                when (noticeType) {
                    "device_added" -> strings.deviceAdded
                    "device_removed" -> strings.deviceRemoved
                    else -> strings.safetyCodeChanged
                }
            )
            noticePayload?.optString("username")?.takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
            noticePayload?.optString("device_name")?.takeIf { it.isNotBlank() }?.let {
                append(" / ")
                append(it)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                noticeText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    val mine = when {
        message.userCode.isNotBlank() && state.me?.userCode?.isNotBlank() == true -> message.userCode == state.me?.userCode
        else -> message.username == state.me?.username
    }
    val showSenderAvatar = !mine && conversationKind != "direct"
    val replyEnabled = message.kind != "recalled" && message.kind != "attachment_cleared"
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val maxSwipePx = with(density) { 88.dp.toPx() }
    var swipeTarget by remember(message.id) { mutableStateOf(0f) }
    var showRecallMenu by remember(message.id) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = swipeTarget, label = "replySwipe")
    val replyIconAlpha = if (replyEnabled) ((animatedOffset.absoluteValue / swipeThresholdPx).coerceIn(0f, 1f) * 0.95f) else 0f
    val decryptSecret = ""
    val bubbleColor = if (mine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val bubbleMutedColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleContentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val canShowReceipts = mine && message.localSendState == LocalSendState.SENT && message.id > 0L
    val isDeliveredToAll = canShowReceipts &&
        participantUsernames.isNotEmpty() &&
        participantUsernames.all { (state.currentConversationDeliveryStates[it] ?: 0L) >= message.id }
    val isReadByAll = canShowReceipts &&
        participantUsernames.isNotEmpty() &&
        participantUsernames.all { (state.currentConversationReadStates[it] ?: 0L) >= message.id }
    val isReadByAny = canShowReceipts &&
        participantUsernames.isNotEmpty() &&
        participantUsernames.any {
            (state.currentConversationReadStates[it] ?: 0L) >= message.id
        }
    val showDoubleTick = if (conversationKind == "group") {
        isReadByAny || isReadByAll
    } else {
        isDeliveredToAll || isReadByAll
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (showSenderAvatar) {
            AvatarBubble(
                imageUrl = sender?.avatarUrl.orEmpty(),
                fallback = message.username,
                colorHex = sender?.color ?: message.color,
                size = 34.dp,
                serverUrl = state.serverUrl,
                modifier = Modifier.clickable(enabled = (sender?.userCode ?: message.userCode).isNotBlank()) {
                    val userCode = sender?.userCode ?: message.userCode
                    if (userCode.isNotBlank()) onSenderAvatarClick(userCode)
                }
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showRecallMenu) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    TextButton(
                        onClick = {
                            showRecallMenu = false
                            onRecall(message)
                        }
                    ) {
                        Text(strings.recallAction)
                    }
                }
            }
            Box(
                modifier = Modifier.padding(
                    start = if (mine) 0.dp else 18.dp,
                    end = if (mine) 18.dp else 0.dp
                ),
                contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = replyIconAlpha),
                    modifier = Modifier
                        .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                        .offset(x = if (mine) 6.dp else (-6).dp)
                        .size(18.dp)
                )
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = bubbleColor,
                        contentColor = bubbleContentColor
                    ),
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                        .then(
                            if (replyEnabled) {
                                Modifier.pointerInput(message.id, mine) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            val shouldReply = if (mine) animatedOffset <= -swipeThresholdPx else animatedOffset >= swipeThresholdPx
                                            if (shouldReply) onReply(message)
                                            swipeTarget = 0f
                                        },
                                        onDragCancel = { swipeTarget = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val next = swipeTarget + dragAmount
                                        swipeTarget = if (mine) next.coerceIn(-maxSwipePx, 0f) else next.coerceIn(0f, maxSwipePx)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                        .combinedClickable(
                            onLongClick = {
                                if (mine && message.kind != "recalled" && message.kind != "attachment_cleared") {
                                    showRecallMenu = !showRecallMenu
                                }
                            },
                            onClick = { showRecallMenu = false }
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!mine) Text(message.username, color = Color(android.graphics.Color.parseColor(sender?.color ?: message.color)), fontSize = 12.sp)
                        message.replyTo?.let {
                            val originalMessage = messagesById[it.id]
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (mine) {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                                    }
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                    Text("${strings.replyingTo} ${it.username}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                                    ReplyPreviewText(
                                        it.preview,
                                        originalMessage,
                                        decryptSecret,
                                        strings,
                                        decryptedTextCache,
                                        attachmentMetaCache,
                                        localPrefs,
                                        bubbleMutedColor,
                                        11.sp
                                    )
                                }
                            }
                        }
                        when (message.kind) {
                            "text" -> MessageText(message, decryptSecret, strings, decryptedTextCache, localPrefs)
                            "recalled" -> Text(strings.recalledMessage, color = bubbleMutedColor)
                            "attachment_cleared" -> Text(strings.attachmentRemoved, color = bubbleMutedColor)
                            "image", "photo", "audio", "file" -> AttachmentBubble(
                                message = message,
                                secret = decryptSecret,
                                strings = strings,
                                attachmentMetaCache = attachmentMetaCache,
                                localPrefs = localPrefs,
                                serverUrl = state.serverUrl,
                                onOpenAttachment = onOpenAttachment,
                                onPreviewImage = onPreviewImage,
                                onPlayAudio = onPlayAudio,
                                isAudioPlaying = isAudioPlaying,
                                hasUnreadAudioIndicator = hasUnreadAudioIndicator
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatMessageTime(message.ts), color = bubbleMutedColor, fontSize = 10.sp)
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                when (message.localSendState) {
                                    LocalSendState.SENT -> ReadReceiptIcon(
                                        doubleTick = showDoubleTick,
                                        tint = if (isReadByAll) WaReadReceiptBlue else bubbleMutedColor
                                    )
                                    LocalSendState.SENDING -> CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = bubbleMutedColor,
                                        strokeWidth = 1.6.dp
                                    )
                                    LocalSendState.FAILED -> Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .clickable { onRetryTap(message) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "!",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontSize = 12.sp,
                                            modifier = Modifier.offset(y = (-0.5).dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadReceiptIcon(doubleTick: Boolean, tint: Color) {
    if (!doubleTick) {
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        return
    }

    Box(modifier = Modifier.width(18.dp).height(14.dp)) {
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 0.dp)
                .size(12.dp)
        )
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-1).dp)
                .size(12.dp)
        )
    }
}

@Composable
private fun MessageText(
    message: ChatMessage,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    localPrefs: ChatPreferences,
) {
    val text by produceState(
        initialValue = decryptedTextCache[message.id] ?: if (message.e2ee) strings.encryptedMessage else message.payload,
        key1 = message.id,
        key2 = secret,
        key3 = strings
    ) {
        value = decryptedTextCache[message.id] ?: if (!message.e2ee) {
            message.payload
        } else if (DirectMessageCrypto.isDirectPayload(message.payload)) {
            val decrypted = withContext(Dispatchers.Default) {
                runCatching { DirectMessageCrypto.decryptText(localPrefs, message) }.getOrNull()
            }
            decrypted?.also { decryptedTextCache[message.id] = it } ?: strings.unableToDecrypt
        } else if (GroupSenderKeyCrypto.isGroupPayload(message.payload)) {
            val decrypted = withContext(Dispatchers.Default) {
                runCatching { GroupSenderKeyCrypto.decryptText(localPrefs, message.conversationId, message.payload) }.getOrNull()
            }
            decrypted?.also { decryptedTextCache[message.id] = it } ?: strings.unableToDecrypt
        } else {
            strings.encryptedMessage
        }
    }
    Text(annotatedMentions(text), fontSize = 15.sp, lineHeight = 19.sp)
}

@Composable
private fun ReplyPreviewText(
    fallbackPreview: String,
    originalMessage: ChatMessage?,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    color: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val previewColor = color ?: androidx.compose.material3.LocalContentColor.current
    val preview by produceState(initialValue = fallbackPreview.ifBlank { strings.encryptedMessage }, fallbackPreview, originalMessage?.id, secret, strings) {
        value = if (originalMessage == null) {
            fallbackPreview.ifBlank { strings.encryptedMessage }
        } else {
            resolvePreviewText(originalMessage, fallbackPreview, secret, strings, decryptedTextCache, attachmentMetaCache, localPrefs)
        }
    }
    Text(preview, color = previewColor, fontSize = fontSize)
}

@Composable
private fun AttachmentBubble(
    message: ChatMessage,
    secret: String,
    strings: AppStrings,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    serverUrl: String,
    onOpenAttachment: (ChatMessage) -> Unit,
    onPreviewImage: (ChatMessage) -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    isAudioPlaying: Boolean,
    hasUnreadAudioIndicator: Boolean,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val previewTargetPx = remember(density) { with(density) { 240.dp.roundToPx() } }
    val payload = remember(message.payload) { parseJson(message.payload) }
    val metaJson by produceState<JSONObject?>(initialValue = attachmentMetaCache[message.id], key1 = message.id, key2 = secret) {
        value = attachmentMetaCache[message.id] ?: if (!message.e2ee || payload?.has("meta") != true) {
            JSONObject()
                .put("name", payload?.optString("name").orEmpty().ifBlank { payload?.optJSONObject("attachment")?.optString("file").orEmpty() })
                .put(
                    "mime",
                    payload?.optString("mime").orEmpty()
                        .ifBlank { payload?.optJSONObject("attachment")?.optString("mime").orEmpty() }
                )
                .put("size", payload?.optLong("size") ?: 0L)
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val metaCipher = payload?.optString("meta").orEmpty()
                    if (metaCipher.isBlank()) {
                        null
                    } else {
                        decryptAttachmentMeta(message, localPrefs)
                    }
                }.getOrNull()
            }.also { attachmentMetaCache[message.id] = it }
        }
    }
    val previewBitmap by produceState<Bitmap?>(initialValue = null, key1 = message.id, key2 = secret, key3 = serverUrl) {
        value = if (message.kind == "image" || message.kind == "photo") {
            AttachmentBitmapStore.load(context, serverUrl, message, localPrefs, previewTargetPx)
        } else {
            null
        }
    }
    if (message.kind == "image" || message.kind == "photo") {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPreviewImage(message) }
        ) {
            previewBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                )
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (message.e2ee && (payload?.optInt("v", 0) ?: 0) < 3) strings.encryptedAttachment else strings.image,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    val attachmentName = when (message.kind) {
        "image" -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.image } }
        "audio" -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.voiceNote } }
        else -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.file } }
    }
    if (message.kind != "audio") {
        Text(attachmentName)
    }
    val sizeLine = buildString {
        val size = (metaJson?.optLong("size") ?: 0L).takeIf { it > 0L } ?: message.localAttachmentSize
        if (message.kind != "audio" && size > 0L) append(formatSize(size))
        val mime = metaJson?.optString("mime").orEmpty().ifBlank { message.localAttachmentMime }
        if (message.kind != "audio" && mime.isNotBlank()) {
            if (isNotEmpty()) append(" | ")
            append(mime)
        }
        val duration = (metaJson?.optLong("durationMs") ?: 0L).takeIf { it > 0L } ?: message.localAttachmentDurationMs
        if (duration > 0L) {
            if (isNotEmpty()) append(" | ")
            append(formatDuration(duration))
        }
    }
    if (sizeLine.isNotBlank()) Text(sizeLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    else if (message.kind != "audio") Text(strings.tapOpen, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    if (message.kind == "audio") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { onPlayAudio(message) }) {
                Icon(if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (isAudioPlaying) strings.pause else strings.play)
            }
            Text(
                metaJson?.optLong("durationMs")?.takeIf { it > 0L }?.let(::formatDuration) ?: strings.voiceNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (hasUnreadAudioIndicator) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
        }
    } else {
        FilledTonalButton(
            onClick = {
                if (message.kind == "image" || message.kind == "photo") onPreviewImage(message)
                else onOpenAttachment(message)
            }
        ) {
            Text(if (message.kind == "file") strings.downloadOpen else strings.open)
        }
    }
}

private object AttachmentBitmapStore {
    private val memoryCache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 24L) / 1024L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(
        context: Context,
        serverUrl: String,
        message: ChatMessage,
        localPrefs: ChatPreferences,
        targetSizePx: Int,
    ): Bitmap? {
        if (message.kind != "image" && message.kind != "photo") return null
        val key = "${message.id}:${message.localAttachmentPath}:$targetSizePx"
        memoryCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            message.localAttachmentPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }?.let { localFile ->
                return@withContext decodeAttachmentBitmap(localFile, targetSizePx)?.also { memoryCache.put(key, it) }
            }
            val attachment = runCatching {
                resolveAttachment(
                    context = context,
                    api = ChatApi(),
                    serverUrl = serverUrl,
                    message = message,
                    prefs = localPrefs,
                    exportToDownloads = false
                )
            }.getOrNull() ?: return@withContext null
            val file = attachment.file ?: return@withContext null
            decodeAttachmentBitmap(file, targetSizePx)?.also { memoryCache.put(key, it) }
        }
    }

    private fun decodeAttachmentBitmap(file: File, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds, targetSizePx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateSampleSize(options: BitmapFactory.Options, targetSizePx: Int): Int {
        val maxTarget = targetSizePx.coerceAtLeast(240)
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / 2 >= maxTarget && height / 2 >= maxTarget) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}

@Composable
private fun ImagePreviewDialog(
    message: ChatMessage,
    localPrefs: ChatPreferences,
    strings: AppStrings,
    serverUrl: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val previewTargetPx = remember(configuration.screenWidthDp, density) {
        with(density) { (configuration.screenWidthDp.dp * 2.4f).roundToPx() }
    }
    val previewBitmap by produceState<Bitmap?>(initialValue = null, message.id, serverUrl, previewTargetPx) {
        value = AttachmentBitmapStore.load(context, serverUrl, message, localPrefs, previewTargetPx)
    }
    var scale by remember(message.id) { mutableStateOf(1f) }
    var offset by remember(message.id) { mutableStateOf(Offset.Zero) }

    AnimatedDialogContainer(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) { dismiss ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            previewBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(message.id) {
                            detectTapGestures(
                                onTap = {
                                    if (scale > 1.02f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        dismiss()
                                    }
                                }
                            )
                        }
                        .pointerInput(message.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                scale = nextScale
                                offset = if (nextScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    offset + pan * 1.75f
                                }
                            }
                        }
                )
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            if (message.localAttachmentPath.isBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 112.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.96f)
                ) {
                    TextButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = strings.downloadOpen,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private object AvatarBitmapStore {
    private val memoryCache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 32L) / 1024L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(context: Context, serverUrl: String, imageUrl: String, targetSizePx: Int): Bitmap? {
        if (imageUrl.isBlank()) return null
        val resolvedUrl = if (imageUrl.startsWith("http")) imageUrl else serverUrl.trimEnd('/') + imageUrl
        memoryCache.get(resolvedUrl)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cacheFile = File(File(context.cacheDir, "avatar-cache").apply { mkdirs() }, md5(resolvedUrl) + ".img")
            val bitmap = when {
                cacheFile.exists() -> decodeAvatarBitmap(cacheFile.readBytes(), targetSizePx)
                else -> runCatching { URL(resolvedUrl).openStream().use { it.readBytes() } }
                    .getOrNull()
                    ?.also { cacheFile.writeBytes(it) }
                    ?.let { decodeAvatarBitmap(it, targetSizePx) }
            }
            bitmap?.also { memoryCache.put(resolvedUrl, it) }
        }
    }

    private fun decodeAvatarBitmap(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = calculateSampleSize(bounds, targetSizePx)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSampleSize(options: BitmapFactory.Options, targetSizePx: Int): Int {
        val maxTarget = targetSizePx.coerceAtLeast(160)
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / 2 >= maxTarget && height / 2 >= maxTarget) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Composable
private fun AvatarBubble(
    imageUrl: String,
    fallback: String,
    colorHex: String,
    size: Dp,
    serverUrl: String = DEFAULT_SERVER_URL,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val targetSizePx = remember(size, density) { with(density) { (size * 2f).roundToPx() } }
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUrl, serverUrl, targetSizePx) {
        value = AvatarBitmapStore.load(context, serverUrl, imageUrl, targetSizePx)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color(android.graphics.Color.parseColor(colorHex)))
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } else {
            Text(fallback.take(1).uppercase(), color = Color.White, fontSize = (size.value / 2.4f).sp)
        }
    }
}

@Composable
private fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        FilterChip(
            selected = language == AppLanguage.ZH,
            onClick = { onSelect(AppLanguage.ZH) },
            label = { Text("\u4e2d\u6587") }
        )
        FilterChip(
            selected = language == AppLanguage.EN,
            onClick = { onSelect(AppLanguage.EN) },
            label = { Text("English") }
        )
    }
}

@Composable
private fun DisplayModeSelector(mode: AppDisplayMode, onSelect: (AppDisplayMode) -> Unit, strings: AppStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.displayMode, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == AppDisplayMode.SYSTEM,
                onClick = { onSelect(AppDisplayMode.SYSTEM) },
                label = { Text(strings.followSystem) }
            )
            FilterChip(
                selected = mode == AppDisplayMode.LIGHT,
                onClick = { onSelect(AppDisplayMode.LIGHT) },
                label = { Text(strings.lightMode) }
            )
            FilterChip(
                selected = mode == AppDisplayMode.DARK,
                onClick = { onSelect(AppDisplayMode.DARK) },
                label = { Text(strings.darkMode) }
            )
        }
    }
}

private fun currentMentionQuery(value: TextFieldValue): String? {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val beforeCursor = value.text.substring(0, cursor)
    val atIndex = beforeCursor.lastIndexOf('@')
    if (atIndex < 0) return null
    val query = beforeCursor.substring(atIndex + 1)
    if (query.length > 32) return null
    if (query.any { !(it.isLetterOrDigit() || it == '_' || it == '.' || it == '-') }) return null
    return query
}

private fun replaceMention(value: TextFieldValue, username: String): TextFieldValue {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val beforeCursor = value.text.substring(0, cursor)
    val afterCursor = value.text.substring(cursor)
    val atIndex = beforeCursor.lastIndexOf('@')
    if (atIndex < 0) return value
    val query = beforeCursor.substring(atIndex + 1)
    if (query.length > 32) return value
    if (query.any { !(it.isLetterOrDigit() || it == '_' || it == '.' || it == '-') }) return value
    val newText = value.text.substring(0, atIndex) + "@$username " + afterCursor
    val newCursor = (atIndex + username.length + 2).coerceAtMost(newText.length)
    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursor)
    )
}

private suspend fun resolvePreviewText(
    message: ChatMessage,
    fallbackPreview: String,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
): String = when (message.kind) {
    "text" -> {
        if (!message.e2ee) message.payload.take(80)
        else if (DirectMessageCrypto.isDirectPayload(message.payload)) {
            val fullText = decryptedTextCache[message.id] ?: withContext(Dispatchers.Default) {
                runCatching { DirectMessageCrypto.decryptText(localPrefs, message) }.getOrNull()
            }?.also { decryptedTextCache[message.id] = it } ?: fallbackPreview.ifBlank { strings.unableToDecrypt }
            fullText.take(80)
        }
        else if (GroupSenderKeyCrypto.isGroupPayload(message.payload)) {
            val fullText = decryptedTextCache[message.id] ?: withContext(Dispatchers.Default) {
                runCatching { GroupSenderKeyCrypto.decryptText(localPrefs, message.conversationId, message.payload) }.getOrNull()
            }?.also { decryptedTextCache[message.id] = it } ?: fallbackPreview.ifBlank { strings.unableToDecrypt }
            fullText.take(80)
        }
        else fallbackPreview.ifBlank { strings.encryptedMessage }
    }
    "recalled" -> strings.recalledMessage
    "attachment_cleared" -> strings.attachmentRemoved
    "image", "photo" -> resolveAttachmentMeta(message, secret, attachmentMetaCache, localPrefs)?.optString("name").orEmpty().ifBlank { strings.image }
    "audio" -> resolveAttachmentMeta(message, secret, attachmentMetaCache, localPrefs)?.optString("name").orEmpty().ifBlank { strings.voiceNote }
    "file" -> resolveAttachmentMeta(message, secret, attachmentMetaCache, localPrefs)?.optString("name").orEmpty().ifBlank { strings.file }
    else -> fallbackPreview.ifBlank { message.kind }
}

private suspend fun resolveAttachmentMeta(
    message: ChatMessage,
    secret: String,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
): JSONObject? {
    attachmentMetaCache[message.id]?.let { return it }
    if (message.localAttachmentName.isNotBlank() || message.localAttachmentMime.isNotBlank() || message.localAttachmentSize > 0L) {
        return JSONObject()
            .put("name", message.localAttachmentName)
            .put("mime", message.localAttachmentMime)
            .put("size", message.localAttachmentSize)
            .put("durationMs", message.localAttachmentDurationMs)
            .also { attachmentMetaCache[message.id] = it }
    }
    val payload = parseJson(message.payload) ?: return null
    val meta = withContext(Dispatchers.Default) {
        runCatching {
            decryptAttachmentMeta(message, localPrefs)
        }.getOrNull()
    }
    attachmentMetaCache[message.id] = meta
    return meta
}

private fun parseJson(text: String) = runCatching { JSONObject(text) }.getOrNull()

private fun annotatedMentions(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Regex("@([A-Za-z0-9_.-]{1,32})").findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        pushStyle(SpanStyle(color = WaPrimary))
        append(match.value)
        pop()
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private fun buildStatusLine(state: ChatUiState, strings: AppStrings): String {
    val username = state.me?.username.orEmpty()
    val adminSuffix = if (state.me?.isAdmin == true) " ${strings.admin}" else ""
    val status = if (state.isConnected) strings.connected else strings.disconnected
    return listOf(
        username + adminSuffix,
        status
    ).filterNotNull().joinToString(strings.statusSeparator)
}

private fun clampAvatarCropOffset(bitmap: Bitmap, viewportPx: Float, scale: Float, proposed: Offset): Offset {
    val baseScale = maxOf(viewportPx / bitmap.width.toFloat(), viewportPx / bitmap.height.toFloat())
    val scaledWidth = bitmap.width * baseScale * scale
    val scaledHeight = bitmap.height * baseScale * scale
    val maxX = ((scaledWidth - viewportPx) / 2f).coerceAtLeast(0f)
    val maxY = ((scaledHeight - viewportPx) / 2f).coerceAtLeast(0f)
    return Offset(
        x = proposed.x.coerceIn(-maxX, maxX),
        y = proposed.y.coerceIn(-maxY, maxY)
    )
}

private fun cropAvatarBitmap(
    bitmap: Bitmap,
    scale: Float,
    offset: Offset,
    viewportPx: Float,
    outputSize: Int = 512,
): ByteArray? {
    if (bitmap.width <= 0 || bitmap.height <= 0 || viewportPx <= 0f) return null
    val safeScale = scale.coerceIn(1f, 4.5f)
    val safeOffset = clampAvatarCropOffset(bitmap, viewportPx, safeScale, offset)
    val baseScale = maxOf(viewportPx / bitmap.width.toFloat(), viewportPx / bitmap.height.toFloat())
    val previewScale = baseScale * safeScale
    val previewLeft = (viewportPx - bitmap.width * previewScale) / 2f + safeOffset.x
    val previewTop = (viewportPx - bitmap.height * previewScale) / 2f + safeOffset.y
    val outputRatio = outputSize / viewportPx
    val matrix = Matrix().apply {
        postScale(previewScale * outputRatio, previewScale * outputRatio)
        postTranslate(previewLeft * outputRatio, previewTop * outputRatio)
    }
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    return try {
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            stream.toByteArray()
        }
    } finally {
        output.recycle()
    }
}

private fun queryName(context: Context, uri: android.net.Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun startRecording(context: Context, onStarted: (MediaRecorder, File, Long) -> Unit) {
    val output = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
    val recorder = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setOutputFile(output.absolutePath)
        prepare()
        start()
    }
    onStarted(recorder, output, System.currentTimeMillis())
}

private fun rotationFromOrientation(orientation: Int): Int? = when {
    orientation == OrientationEventListener.ORIENTATION_UNKNOWN -> null
    orientation in 45..134 -> Surface.ROTATION_270
    orientation in 135..224 -> Surface.ROTATION_180
    orientation in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

private fun normalizeCapturedPhoto(file: File, maxDimensionPx: Int = 2560) {
    if (!file.exists()) return
    val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return
    val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val sampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    if (rotation == 0f && sampleSize <= 1) return

    val original = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    ) ?: return
    val rotated = if (rotation == 0f) {
        original
    } else {
        runCatching {
            Bitmap.createBitmap(
                original,
                0,
                0,
                original.width,
                original.height,
                Matrix().apply { postRotate(rotation) },
                true
            )
        }.getOrNull() ?: run {
            original.recycle()
            return
        }
    }

    if (rotated != original) {
        original.recycle()
    }

    file.outputStream().use { output ->
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    rotated.recycle()

    runCatching {
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
    }
}

private fun decodeScaledBitmap(file: File, targetSizePx: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
        }
    )
}

private fun calculateBitmapSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
    if (width <= 0 || height <= 0) return 1
    val maxTarget = targetSizePx.coerceAtLeast(720)
    var sampleSize = 1
    var currentWidth = width
    var currentHeight = height
    while (currentWidth / 2 >= maxTarget && currentHeight / 2 >= maxTarget) {
        currentWidth /= 2
        currentHeight /= 2
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}

private suspend fun openAttachment(context: Context, viewModel: ChatViewModel, message: ChatMessage) {
    runCatching {
        val attachment = viewModel.materializeAttachment(context, message)
        val uri = attachment.uri ?: attachment.file?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        } ?: throw IllegalStateException("Unable to access downloaded file")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        viewModel.reportError(it.message ?: "Unable to open attachment")
    }
    viewModel.clearDownloadProgress()
}

private suspend fun downloadAndOpenAttachment(context: Context, viewModel: ChatViewModel, message: ChatMessage) {
    runCatching {
        val attachment = viewModel.materializeAttachment(context, message, exportToDownloads = false)
        val uri = attachment.uri ?: attachment.file?.let {
            ensureAttachmentInDownloads(context, message.id, attachment.name, attachment.mime, it.readBytes())
        } ?: throw IllegalStateException("Unable to access downloaded file")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        viewModel.reportError(it.message ?: "Unable to download attachment")
    }
    viewModel.clearDownloadProgress()
}

private suspend fun downloadLatestUpdate(context: Context, viewModel: ChatViewModel) {
    runCatching {
        viewModel.downloadLatestAppRelease(context)
    }.onFailure {
        viewModel.reportError(it.message ?: "Unable to download update")
    }
}

private suspend fun downloadLatestPrerelease(context: Context, viewModel: ChatViewModel) {
    runCatching {
        viewModel.downloadLatestPrerelease(context)
    }.onFailure {
        viewModel.reportError(it.message ?: "Unable to download prerelease")
    }
}

private fun openInstaller(context: Context, viewModel: ChatViewModel, attachment: DecryptedAttachment) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            throw IllegalStateException("Allow installs from this app, then tap Install update again")
        }
        val installUri = attachment.file?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        } ?: attachment.uri ?: throw IllegalStateException("Unable to access update package")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = installUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        context.startActivity(intent)
    }.onFailure {
        viewModel.reportError(it.message ?: "Unable to install update")
    }
}

private fun releaseDisplayVersion(release: AppReleaseInfo?): String =
    release?.versionLabel?.ifBlank { release.version.ifBlank { "-" } } ?: "-"

private fun releaseDownloadFileName(release: AppReleaseInfo?): String? {
    release ?: return null
    return release.fileName.ifBlank {
        release.originalName.ifBlank {
            when (release.channel) {
                AppReleaseChannel.STABLE -> if (release.version.isNotBlank()) "e2eechat-release-${release.version}.apk" else "update.apk"
                AppReleaseChannel.PRERELEASE -> if (release.version.isNotBlank()) "e2eechat-prerelease-${release.version}(pre).apk" else "e2eechat-prerelease.apk"
            }
        }
    }
}

private fun compareVersionNames(left: String, right: String): Int {
    fun parseVersion(value: String): Pair<List<Int>, Boolean> {
        val normalized = value.trim()
        val prerelease = normalized.contains("(pre)", ignoreCase = true) || normalized.contains("prerelease", ignoreCase = true)
        val digits = Regex("\\d+").findAll(normalized).map { it.value.toIntOrNull() ?: 0 }.toList()
        return digits to prerelease
    }
    val (leftParts, leftPrerelease) = parseVersion(left)
    val (rightParts, rightPrerelease) = parseVersion(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val l = leftParts.getOrElse(index) { 0 }
        val r = rightParts.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    if (leftPrerelease != rightPrerelease) {
        return if (leftPrerelease) -1 else 1
    }
    return 0
}

private fun currentAppVersion(context: Context): String =
    runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName?.ifBlank { "0.0.0" } ?: "0.0.0"
    }.getOrDefault("0.0.0")

@Composable
private fun TimelineSeparator(label: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

private fun shouldShowTimelineSeparator(previousTs: Long, currentTs: Long): Boolean =
    currentTs - previousTs > 3 * 60 * 1000L

private fun formatTimelineLabel(ts: Long, language: AppLanguage, strings: AppStrings): String {
    val locale = when (language) {
        AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.ENGLISH
    }
    val now = System.currentTimeMillis()
    val target = Date(ts)
    val todayKey = SimpleDateFormat("yyyyMMdd", locale).format(Date(now))
    val yesterdayKey = SimpleDateFormat("yyyyMMdd", locale).format(Date(now - 24L * 60L * 60L * 1000L))
    val targetDayKey = SimpleDateFormat("yyyyMMdd", locale).format(target)
    val sameYear = SimpleDateFormat("yyyy", locale).format(target) == SimpleDateFormat("yyyy", locale).format(Date(now))
    val timeText = SimpleDateFormat("HH:mm", locale).format(target)
    return when {
        targetDayKey == todayKey -> "${strings.today} $timeText"
        targetDayKey == yesterdayKey -> "${strings.yesterday} $timeText"
        language == AppLanguage.ZH && sameYear -> "${SimpleDateFormat("M\u6708d\u65e5", locale).format(target)} $timeText"
        language == AppLanguage.ZH -> "${SimpleDateFormat("yyyy\u5e74M\u6708d\u65e5", locale).format(target)} $timeText"
        sameYear -> "${SimpleDateFormat("MMM d", locale).format(target)} $timeText"
        else -> "${SimpleDateFormat("MMM d, yyyy", locale).format(target)} $timeText"
    }
}

private fun formatMessageTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatSize(size: Long): String = when {
    size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024f / 1024f)
    size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024f)
    else -> "$size B"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
