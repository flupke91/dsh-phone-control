package com.flupke.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.core.ConnectionState
import kotlinx.coroutines.launch

/**
 * Onboarding / re-config screen in the DeepSeek-app style: a logo mark, one
 * clean input card, and visible failure reasons.
 */
@Composable
fun ConnectScreen(connection: ConnectionManager, onConnect: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val state by connection.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // App mark
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "D",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "DSH 远程控制",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "连接同一局域网内的 DeepSeek Harness",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text("主机地址", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("http://192.168.x.x:3080") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { onConnect(url) },
                enabled = url.isNotBlank() && state !is ConnectionState.Connecting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                if (state is ConnectionState.Connecting || state is ConnectionState.Reconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Text(" 连接中…")
                } else {
                    Text("连接")
                }
            }
            when (val s = state) {
                is ConnectionState.Reconnecting -> SelectionContainer {
                    Text(
                        "重连中（第 ${s.attempt} 次）：${s.reason}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                is ConnectionState.Failed -> SelectionContainer {
                    Text(
                        "连接失败：${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                else -> {}
            }
            // 一键诊断：自己查连接链路，哪一步断了显示哪一步
            if (url.isNotBlank()) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var diagnosing by remember { mutableStateOf(false) }
                var diagResult by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
                Button(
                    onClick = {
                        diagnosing = true
                        diagResult = null
                        scope.launch {
                            diagResult = runCatching { connection.diagnose(url) }.getOrElse {
                                listOf("诊断执行失败：${it.message}" to false)
                            }
                            diagnosing = false
                        }
                    },
                    enabled = !diagnosing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(if (diagnosing) "诊断中…" else "🔍 一键诊断")
                }
                diagResult?.forEach { (step, ok) ->
                    Text(
                        (if (ok) "✅ " else "❌ ") + step,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Text(
                "需要电脑端安装 dsh-phone-control 插件，并确认：① 手机与电脑在同一 WiFi；② 地址是电脑上 ipconfig 显示的当前 IPv4（电脑 IP 可能因换网/重启而变化）；③ 防火墙 3080 端口已放行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
