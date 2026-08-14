package com.flupke.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flupke.dsh.connect.HostStore
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.core.ConnectionState

// ---------- 设置根页 ----------

@Composable
fun SettingsScreen(
    connection: ConnectionManager,
    hostStore: HostStore,
    vm: DshViewModel,
    onOpenSub: (SettingsPage) -> Unit,
    onDisconnect: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsHeader("设置", onBack = null)
        SettingsRow("连接", subtitle = hostStateLine(connection)) { onOpenSub(SettingsPage.Connection) }
        SettingsRow("外观") { onOpenSub(SettingsPage.Appearance) }
        SettingsRow("对话") { onOpenSub(SettingsPage.Conversation) }
        SettingsRow("插件") { onOpenSub(SettingsPage.Plugins) }
        SettingsRow("关于") { onOpenSub(SettingsPage.About) }
    }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        } else {
            Box(Modifier.size(48.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

// ---------- 设置子页 ----------

@Composable
fun SettingsSubScreen(
    page: SettingsPage,
    connection: ConnectionManager,
    hostStore: HostStore,
    vm: DshViewModel,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (page) {
            SettingsPage.Connection -> {
                SettingsHeader("连接", onBack)
                ConnectionPage(connection, hostStore, onDisconnect)
            }
            SettingsPage.Appearance -> {
                SettingsHeader("外观", onBack)
                AppearancePage()
            }
            SettingsPage.Conversation -> {
                SettingsHeader("对话", onBack)
                ConversationPage()
            }
            SettingsPage.Plugins -> {
                SettingsHeader("插件", onBack)
                PluginsPage(vm)
            }
            SettingsPage.About -> {
                SettingsHeader("关于", onBack)
                AboutPage()
            }
        }
    } // Column
    } // CompositionLocalProvider
}

@Composable
private fun ConnectionPage(connection: ConnectionManager, hostStore: HostStore, onDisconnect: () -> Unit) {
    val state by connection.state.collectAsState()
    val host by hostStore.baseUrl.collectAsState(initial = null)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("当前主机", host ?: "（未配置）")
        val statusText = when (state) {
            is ConnectionState.Connected -> "已连接"
            is ConnectionState.Connecting -> "连接中"
            is ConnectionState.Reconnecting -> "重连中（第 ${(state as ConnectionState.Reconnecting).attempt} 次）：${(state as ConnectionState.Reconnecting).reason}"
            is ConnectionState.Failed -> "连接失败：${(state as ConnectionState.Failed).message}"
            else -> "未连接"
        }
        SelectionContainer {
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text("断开并重新配置")
        }
        if (state is ConnectionState.Failed) {
            Button(onClick = { host?.let { connection.connect(it) } }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("重试连接")
            }
        }
        Text(
            "需要电脑端安装 dsh-phone-control 插件（绑定 0.0.0.0 + 信任围栏自动跟随），并放行防火墙 3080 端口。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppearancePage() {
    val mode by ThemePrefs.mode.collectAsState()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("主题", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        ThemePrefs.Mode.entries.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { ThemePrefs.set(m) }.padding(vertical = 2.dp),
            ) {
                RadioButton(selected = mode == m, onClick = { ThemePrefs.set(m) })
                Text(when (m) {
                    ThemePrefs.Mode.SYSTEM -> "跟随系统"
                    ThemePrefs.Mode.LIGHT -> "浅色"
                    ThemePrefs.Mode.DARK -> "深色"
                })
            }
        }
    }
}

@Composable
private fun ConversationPage() {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("自动生成标题", "会话标题由 DSH 服务端自动生成（session-title），无需 App 本地处理。")
        Card("保存聊天历史", "聊天记录保存在电脑端 DSH（持久化到磁盘），App 仅按需拉取。")
        OutlinedButton(
            onClick = { /* 清空：危险操作，待确认流程 */ },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清空所有聊天记录（M5）", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PluginsPage(vm: DshViewModel) {
    val plugins by vm.plugins.collectAsState()
    val notice by vm.notice.collectAsState()
    LaunchedEffect(Unit) { vm.loadPlugins() }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("主机 Loader 插件清单（只读）", style = MaterialTheme.typography.titleSmall)
        notice?.let {
            SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (plugins.isEmpty()) {
            Text("加载中或暂无插件…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plugins.forEach { p ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.entryId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(p.moduleName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (p.enabled) "启用" else "停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (p.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (p.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AboutPage() {
    val crash = remember { com.flupke.dsh.core.CrashLog.read() }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card("版本", "0.1.0 (M1/M2 迭代)")
        Card(
            "关联项目",
            "dsh-phone-control — 电脑端局域网接入插件（绑定 0.0.0.0 + 信任围栏自动跟随）",
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/flupke91/dsh-phone-control"),
                        ),
                    )
                }
            },
        )
        Card("协议", "与 DSH Web 客户端同协议（/api JSON-RPC + WebSocket 事件流）")
        Card("权限说明", "特权接口（设置/凭据）按 DSH 安全设计仅在电脑端可用，App 端 403 属正常。")
        if (crash != null) {
            Card("崩溃日志（长按可复制）", crash)
            OutlinedButton(onClick = { com.flupke.dsh.core.CrashLog.clear() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("清除崩溃日志")
            }
        }
    }
}

@Composable
private fun Card(title: String, body: String, onClick: (() -> Unit)? = null) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface)
        .padding(14.dp)
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onClick != null) {
                Text(
                    "打开 ↗",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        SelectionContainer {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun hostStateLine(connection: ConnectionManager): String = when (val s = connection.state.value) {
    is ConnectionState.Connected -> "已连接"
    is ConnectionState.Reconnecting -> "重连中…"
    is ConnectionState.Failed -> "失败"
    else -> "未连接"
}
