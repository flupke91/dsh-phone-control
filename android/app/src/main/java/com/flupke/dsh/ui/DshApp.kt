package com.flupke.dsh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flupke.dsh.connect.HostStore
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.core.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun DshApp(
    connection: ConnectionManager,
    hostStore: HostStore,
    debugHost: String? = null,
) {
    val vm: DshViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val host by hostStore.baseUrl.collectAsState(initial = null)
    // 必须 collect 连接状态，否则 LaunchedEffect 的 key 不会在连接成功时重估
    val connState by connection.state.collectAsState()
    val themeMode by ThemePrefs.mode.collectAsState()
    var screen by rememberSaveable { mutableStateOf<Screen>(Screen.Home) }

    val darkTheme = when (themeMode) {
        ThemePrefs.Mode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemePrefs.Mode.LIGHT -> false
        ThemePrefs.Mode.DARK -> true
    }

    // 深色模式下系统栏图标（状态栏/导航栏）反白 + 系统栏背景跟随主题，
    // 避免"白状态栏 + 白图标"或"黑字在黑底上"
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        val barColor = if (darkTheme) android.graphics.Color.rgb(0x0F, 0x11, 0x15) else android.graphics.Color.rgb(0xF5, 0xF6, 0xF7)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
    }

    // 调试/深链主机：存在且本地未配置时直接保存并连接
    LaunchedEffect(debugHost) {
        if (debugHost != null && host == null) {
            scope.launch { hostStore.save(debugHost) }
            if (connection.state.value is ConnectionState.Disconnected) {
                connection.connect(debugHost) { vm.onEvent(it) }
            }
        }
    }
    // 自动连接已保存的主机
    LaunchedEffect(host) {
        if (host != null && connection.state.value is ConnectionState.Disconnected) {
            connection.connect(host!!) { vm.onEvent(it) }
        }
    }
    // 连接成功后绑定并刷新会话
    LaunchedEffect(connState is ConnectionState.Connected) {
        if (connState is ConnectionState.Connected) {
            vm.attach(connection)
            vm.refresh()
        }
    }

    DshTheme(darkTheme = darkTheme) {
        if (host == null) {
            ConnectScreen(
                connection = connection,
                onConnect = { url ->
                    connection.connect(url) { vm.onEvent(it) }
                    scope.launch { hostStore.save(url) }
                },
            )
            return@DshTheme
        }

        BackHandler(enabled = screen != Screen.Home) {
            screen = when (screen) {
                is Screen.SettingsSub -> Screen.Settings
                is Screen.Conversation -> {
                    vm.closeSession()
                    Screen.Home
                }
                else -> Screen.Home
            }
        }

        // safeDrawingPadding：Android 15+ 强制 edge-to-edge，避免状态栏/导航栏遮挡
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            when (screen) {
                Screen.Home -> SessionsScreen(
                    connection = connection,
                    vm = vm,
                    onOpenSession = { id ->
                        vm.openSession(id)
                        screen = Screen.Conversation
                    },
                    onOpenSettings = { screen = Screen.Settings },
                )
                Screen.Conversation -> ChatScreen(
                    connection = connection,
                    vm = vm,
                    onBack = {
                        vm.closeSession()
                        screen = Screen.Home
                    },
                )
                Screen.Settings -> SettingsScreen(
                    connection = connection,
                    hostStore = hostStore,
                    vm = vm,
                    onOpenSub = { screen = Screen.SettingsSub(it) },
                    onDisconnect = {
                        connection.disconnect()
                        scope.launch { hostStore.clear() }
                    },
                )
                is Screen.SettingsSub -> SettingsSubScreen(
                    page = (screen as Screen.SettingsSub).page,
                    connection = connection,
                    hostStore = hostStore,
                    vm = vm,
                    onBack = { screen = Screen.Settings },
                    onDisconnect = {
                        connection.disconnect()
                        scope.launch { hostStore.clear() }
                    },
                )
            }
        }
    }
}
