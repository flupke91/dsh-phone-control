package com.flupke.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.data.title
import kotlinx.coroutines.launch

/**
 * 主页面（仿 DeepSeek APP）：空状态/会话视图 + 底部输入栏 + 模型选择。
 * DSH 原生具备联网搜索与深度思考，App 不再提供对应开关。
 */
@Composable
fun ChatScreen(connection: ConnectionManager, vm: DshViewModel, onBack: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentSessionId.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val modelGroups by vm.modelGroups.collectAsState()
    val notice by vm.notice.collectAsState()
    var showModelPicker by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    val currentSession = sessions.find { it.sessionId == currentId }
    val title = currentSession?.title() ?: "新对话"

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 强制内容色 = onSurface：防止上层 CompositionLocal 污染导致黑字（深色下黑字问题根因）
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
        // 顶栏：返回 / 标题 / 模型选择 / 更多
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (currentId != null) {
                Text(
                    currentModel ?: "选择模型",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { showModelPicker = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            IconButton(onClick = { vm.newSession() }) {
                Icon(Icons.Filled.Add, contentDescription = "新对话")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // 主体：消息流（weight(1f) 占满剩余空间，输入栏保持在底部可见）
        if (currentId == null) {
            EmptyChatState(modifier = Modifier.weight(1f))
        } else {
            MessageFlow(connection = connection, vm = vm, modifier = Modifier.weight(1f))
            TaskBoard(vm)
        }

        // 输入控制行：运行状态 + 停止按钮
        if (currentId != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                val running = sessions.find { it.sessionId == currentId }?.running == true
                if (running) {
                    Text(
                        "● 运行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable { vm.cancelCurrent() }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
        // 底部输入栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { /* 附件 M3 */ }) {
                Icon(Icons.Filled.Add, contentDescription = "附件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (currentId == null) "输入消息，开始新对话…" else "输入消息…") },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (currentId == null) {
                        // 无会话：先新建（会话就绪后再输入发送）
                        vm.newSession()
                    } else {
                        vm.sendMessage(input)
                    }
                    input = ""
                },
                enabled = input.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (currentId != null) {
            Text(
                "消息将发送到电脑上的 DSH，由其 Agent 处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        notice?.let {
            SelectionContainer {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        } // CompositionLocalProvider
    } // Column

    if (showModelPicker && currentId != null) {
        ModelPickerDialog(
            currentModel = currentModel,
            groups = modelGroups,
            onSelect = { provider, model -> vm.selectModel(provider, model); showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun EmptyChatState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("D", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "嗨，我是 DSH",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "连接电脑上的 DeepSeek Harness，随时监控与对话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ConversationPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "会话详情（消息流 / 工具调用 / 任务）将在 M3 呈现",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- M3: 消息流 ----------

@Composable
private fun MessageFlow(connection: ConnectionManager, vm: DshViewModel, modifier: Modifier = Modifier) {
    val messages by vm.messages.collectAsState()
    val loading by vm.historyLoading.collectAsState()
    val notice by vm.notice.collectAsState()

    if (loading && messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (notice != null) "加载失败：$notice" else "暂无消息",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // reverseLayout：index 0 在最底部 = 最新消息，天然停在最新并自动跟随
        items(messages.asReversed(), key = { it.id }) { m ->
            when (m.role) {
                "user" -> UserBubble(m.text)
                else -> AssistantBlock(m)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // 深色模式下用深底浅字（primaryContainer/onPrimaryContainer），
            // 避免浅蓝气泡+深色字在深色主题下刺眼
            SelectionContainer {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        CopyAffordance(text)
    }
}

@Composable
private fun AssistantBlock(m: com.flupke.dsh.data.ChatMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 思考块（可折叠，仿 DSH Web 的 Think 块）
        m.reasoning?.let { reasoning ->
            var expanded by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (expanded) "思考 ▾" else "思考 ▸",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${reasoning.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    SelectionContainer {
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        // 正文
        if (m.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // 工具调用行
        m.toolCalls.forEach { t -> ToolCallRow(t) }
        // 左下角一键复制
        val copyText = buildString {
            if (m.reasoning != null) append("[思考] ").append(m.reasoning).append("\n\n")
            append(m.text)
            m.toolCalls.forEach { t ->
                append("\n\n[工具] ").append(t.name).append("\n").append(t.args)
                t.result?.let { append("\n").append(it) }
            }
        }
        CopyAffordance(copyText)
    }
}

/** M4 任务看板：当前会话的后台任务（session/jobs 帧驱动）。 */
@Composable
private fun TaskBoard(vm: DshViewModel) {
    val jobs by vm.jobs.collectAsState()
    var expanded by remember { mutableStateOf(true) }
    if (jobs.isEmpty()) return
    val running = jobs.count { it.status == "running" || it.status == "stopping" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "📋 任务看板（${jobs.size}，运行 $running）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            jobs.forEach { job ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚙ ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            job.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        job.detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val (statusText, statusColor) = when (job.status) {
                        "running" -> "● 运行中" to MaterialTheme.colorScheme.primary
                        "stopping" -> "● 停止中" to DshWarning
                        "completed" -> "✓ 完成" to MaterialTheme.colorScheme.primary
                        "killed" -> "✕ 已终止" to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> "✕ 失败" to MaterialTheme.colorScheme.error
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
        }
    }
}

/** 消息左下角的一键复制按钮。 */
@Composable
private fun CopyAffordance(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Text(
        if (copied) "✓ 已复制" else "⧉ 复制",
        style = MaterialTheme.typography.labelSmall,
        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp)
            .clip(RoundedCornerShape(50))
            .clickable {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    copied = false
                }
            },
    )
}

@Composable
private fun ToolCallRow(t: com.flupke.dsh.data.ToolCallView) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⚙ ${t.name}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (t.result != null) "完成" else "执行中",
                style = MaterialTheme.typography.labelSmall,
                color = if (t.result != null) MaterialTheme.colorScheme.primary else DshWarning,
            )
            Text(
                if (expanded) " ▾" else " ▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    (t.args.take(600) + if (t.args.length > 600) "…" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            t.result?.let { r ->
                SelectionContainer {
                    Text(
                        (r.take(400) + if (r.length > 400) "…" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    currentModel: String?,
    groups: List<com.flupke.dsh.data.ModelGroup>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text("暂无可用模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                groups.forEach { group ->
                    Text(
                        group.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    group.models.forEach { model ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(group.id, model) }
                                .padding(vertical = 2.dp),
                        ) {
                            RadioButton(selected = model == currentModel, onClick = { onSelect(group.id, model) })
                            Text(model, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
