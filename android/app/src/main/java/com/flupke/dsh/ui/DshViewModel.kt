package com.flupke.dsh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.core.NotificationHelper
import com.flupke.dsh.data.ChatMessage
import com.flupke.dsh.data.JobView
import com.flupke.dsh.data.ModelGroup
import com.flupke.dsh.data.PluginEntry
import com.flupke.dsh.data.SessionSummary
import com.flupke.dsh.data.ToolCallView
import com.flupke.dsh.data.title
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DshViewModel(private val app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {

    private companion object {
        const val TAG = "dsh-app"
    }

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    private val _modelGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val modelGroups: StateFlow<List<ModelGroup>> = _modelGroups.asStateFlow()

    private val _plugins = MutableStateFlow<List<PluginEntry>>(emptyList())
    val plugins: StateFlow<List<PluginEntry>> = _plugins.asStateFlow()

    /** 当前会话的消息流（装配后）。 */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 当前会话的后台任务（M4 任务看板，来自 session/jobs 帧）。 */
    private val _jobs = MutableStateFlow<List<JobView>>(emptyList())
    val jobs: StateFlow<List<JobView>> = _jobs.asStateFlow()

    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    /** 可复制的错误/通知文案。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var connection: ConnectionManager? = null
    private var refreshJob: Job? = null

    fun attach(c: ConnectionManager) {
        connection = c
    }

    fun refresh() {
        val c = connection ?: run {
            android.util.Log.d(TAG, "refresh: connection not attached")
            return
        }
        if (_loading.value) {
            android.util.Log.d(TAG, "refresh: skipped (loading)")
            return
        }
        viewModelScope.launch {
            _loading.value = true
            android.util.Log.d(TAG, "refresh: fetching session.list")
            runCatching { c.archivedSessionIds() }
                .onSuccess { archived ->
                    runCatching { c.listSessions() }
                        .onSuccess { list ->
                            // 与 Web 端 sessionVisible 完全一致（web 源码原逻辑）：
                            //   可见 = 非归档 && (非空白 || 是当前会话)
                            // 已归档的（如"局域网手机访问失败/清理c盘/我希望接入deep seek模型"）
                            // 在 web 上不可见，App 也不显示；空白会话只保留当前会话。
                            val shown = list.filter {
                                it.sessionId !in archived &&
                                    (!it.blank || it.sessionId == _currentSessionId.value)
                            }
                            android.util.Log.d(TAG, "refresh: got ${list.size} -> ${shown.size} visible")
                            _sessions.value = shown
                        }
                        .onFailure { e ->
                            android.util.Log.e(TAG, "refresh failed: ${e.message}", e)
                            _notice.value = "刷新会话失败：${e.message}"
                            _sessions.value = emptyList()
                        }
                }
                .onFailure { e ->
                    // 归档集合读取失败：不显示任何会话（宁可空，也不显示已归档的）
                    android.util.Log.e(TAG, "workspace.list failed: ${e.message}", e)
                    _notice.value = "同步工作区失败：${e.message}"
                    _sessions.value = emptyList()
                }
            _loading.value = false
        }
    }

    fun openSession(id: String) {
        _currentSessionId.value = id
        _jobs.value = emptyList()
        loadModels(id)
        loadHistory(id)
    }

    fun loadHistory(sessionId: String) {
        val c = connection ?: return
        if (_historyLoading.value) return
        viewModelScope.launch {
            _historyLoading.value = true
            android.util.Log.d(TAG, "history: fetching $sessionId")
            runCatching { c.history(sessionId) }
                .onSuccess { events ->
                    val msgs = assemble(events)
                    android.util.Log.d(TAG, "history: ${events.size} events -> ${msgs.size} messages")
                    _messages.value = msgs
                }
                .onFailure { e ->
                    android.util.Log.e(TAG, "history failed: ${e.message}", e)
                    _notice.value = "加载聊天记录失败：${e.message}"
                }
            _historyLoading.value = false
        }
    }

    /** 把 history 事件装配成消息流：完整消息为准，工具调用挂到后续 assistant 消息。 */
    private fun assemble(events: List<JsonObject>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val pendingTools = mutableListOf<ToolCallView>()
        for (ev in events) {
            val type = ev["type"]?.jsonPrimitive?.content ?: continue
            val data = ev["data"]?.jsonObject ?: continue
            val time = ev["time"]?.jsonPrimitive?.longOrNull ?: 0L
            when (type) {
                "user/message" -> {
                    val text = joinContentText(data["content"])
                    if (!isHarnessNotice(text)) {
                        out += ChatMessage(
                            id = data["id"]?.jsonPrimitive?.content ?: "u${out.size}",
                            role = "user",
                            text = text,
                            time = time,
                        )
                    }
                }
                "assistant/message" -> {
                    val message = data["message"]?.jsonObject ?: continue
                    val text = joinBlockText(message["content"], "text")
                    if (!isHarnessNotice(text)) {
                        out += ChatMessage(
                            id = "a${out.size}",
                            role = "assistant",
                            text = text,
                            reasoning = joinBlockText(message["content"], "reasoning").ifBlank { null },
                            toolCalls = pendingTools.toList(),
                            time = time,
                        )
                    }
                    pendingTools.clear()
                }
                "tool/call" -> {
                    val name = data["name"]?.jsonPrimitive?.content ?: ""
                    // 隐藏 Harness 内部通知工具（job_output 等），不渲染成工具行
                    if (isInternalTool(name)) {
                        pendingTools.clear()
                    } else {
                        pendingTools += ToolCallView(
                            callId = data["callId"]?.jsonPrimitive?.content ?: "c${pendingTools.size}",
                            name = name,
                            args = data["arguments"]?.jsonPrimitive?.content ?: "",
                        )
                    }
                }
                "tool/result" -> {
                    val src = data["message"]?.jsonObject?.get("source")?.jsonObject
                    val callId = src?.get("callId")?.jsonPrimitive?.content
                    val result = joinBlockText(data["message"]?.jsonObject?.get("content"), "tool-result")
                    if (callId != null) {
                        pendingTools.firstOrNull { it.callId == callId }?.let { it.result = result }
                    }
                }
            }
        }
        // 只保留最近 120 条，避免超大会话拖慢 UI
        return if (out.size > 120) out.takeLast(120) else out
    }

    /** DSH Harness 内部工具（job_output 等），渲染时隐藏。 */
    private fun isInternalTool(name: String): Boolean =
        name == "job_output"

    /**
     * DSH Harness 会把后台任务完成通知（如 "background job pwsh-N … finished …
     * Read its output with job_output"）作为消息注入会话——不是用户/助手真正的内容，
     * 渲染时过滤掉。
     */
    private fun isHarnessNotice(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.startsWith("background job ") || text.startsWith("Background job ")) return true
        if (text.startsWith("The background job ")) return true
        if (text.contains("Read its output with job_output")) return true
        if (text.contains("[status: ") && text.contains("job_output")) return true
        return false
    }

    private fun joinContentText(content: JsonElement?): String =        content?.jsonArray?.mapNotNull { b ->
            if (b.jsonObject["type"]?.jsonPrimitive?.content == "text") {
                b.jsonObject["text"]?.jsonPrimitive?.content
            } else null
        }?.joinToString("\n") ?: ""

    private fun joinBlockText(content: JsonElement?, blockType: String): String =
        content?.jsonArray?.mapNotNull { b ->
            val o = b.jsonObject
            if (o["type"]?.jsonPrimitive?.content == blockType) {
                val text = o["text"]?.jsonPrimitive?.content
                if (text != null && text.isNotBlank()) text else null
            } else null
        }?.joinToString("\n") ?: ""

    fun closeSession() {
        _currentSessionId.value = null
        _modelGroups.value = emptyList()
        _currentModel.value = null
    }

    fun newSession() {
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.createSession() }
                .onSuccess { id ->
                    openSession(id)
                    refresh()
                }
                .onFailure { e -> _notice.value = "新建会话失败：${e.message}" }
        }
    }

    fun renameSession(id: String, title: String) {
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.renameSession(id, title) }
                .onSuccess { refresh() }
                .onFailure { e -> _notice.value = "重命名失败：${e.message}" }
        }
    }

    fun deleteSession(id: String) {
        android.util.Log.d(TAG, "deleteSession called: $id conn=${connection != null}")
        val c = connection ?: return
        viewModelScope.launch {
            android.util.Log.d(TAG, "deleteSession: archiving $id")
            runCatching { c.archiveSession(id) }
                .onSuccess {
                    android.util.Log.d(TAG, "deleteSession: archived ok")
                    if (_currentSessionId.value == id) closeSession()
                    refresh()
                }
                .onFailure { e -> _notice.value = "删除会话失败：${e.message}" }
        }
    }

    fun loadModels(sessionId: String) {
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.sessionModels(sessionId) }
                .onSuccess { (cur, groups) ->
                    _currentModel.value = cur
                    _modelGroups.value = groups
                }
                .onFailure { e -> _notice.value = "加载模型失败：${e.message}" }
        }
    }

    fun selectModel(provider: String, model: String) {
        val id = _currentSessionId.value ?: return
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.selectModel(id, provider, model) }
                .onSuccess { _currentModel.value = model }
                .onFailure { e -> _notice.value = "切换模型失败：${e.message}" }
        }
    }

    fun loadPlugins() {
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.listPlugins() }
                .onSuccess { _plugins.value = it }
                .onFailure { e -> _notice.value = "加载插件失败：${e.message}" }
        }
    }

    /** 发送消息到当前会话（直接投递：steer 模式，立即让 Agent 处理）。 */
    fun sendMessage(text: String) {
        val id = _currentSessionId.value ?: run {
            newSession() // 无会话时先新建
            return
        }
        val c = connection ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        android.util.Log.d(TAG, "send: prompt to $id steer=true -> $trimmed")
        viewModelScope.launch {
            runCatching { c.prompt(id, trimmed, steer = true) }
                .onSuccess { android.util.Log.d(TAG, "send: accepted") }
                .onFailure { e ->
                    android.util.Log.e(TAG, "send failed: ${e.message}", e)
                    _notice.value = "发送失败：${e.message}"
                }
        }
    }

    /** 停止当前会话正在执行的回合（保留队列）。 */
    fun cancelCurrent() {
        val id = _currentSessionId.value ?: return
        val c = connection ?: return
        viewModelScope.launch {
            runCatching { c.cancelSession(id) }
                .onSuccess { android.util.Log.d(TAG, "cancel: sent for $id") }
                .onFailure { e -> _notice.value = "停止失败：${e.message}" }
        }
    }

    /** mux 事件入口：会话列表防抖刷新 + 当前会话消息流实时追加 + 任务看板更新。 */
    fun onEvent(frame: JsonObject) {
        val m = frame["method"]?.toString()?.trim('"') ?: return
        if (!m.startsWith("session/")) return
        // 任务看板：session/jobs 帧（后台任务状态推送）
        if (m == "session/jobs") {
            val payload = frame["payload"]?.jsonObject
            val sid = payload?.get("sessionId")?.jsonPrimitive?.content
            if (sid != null && sid == _currentSessionId.value) {
                val prev = _jobs.value
                _jobs.value = (payload["jobs"]?.jsonArray ?: emptyList()).mapNotNull { j ->
                    val o = j.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    JobView(
                        id = id,
                        kind = o["kind"]?.jsonPrimitive?.content ?: "",
                        label = o["label"]?.jsonPrimitive?.content ?: id,
                        status = o["status"]?.jsonPrimitive?.content ?: "running",
                        detail = o["detail"]?.jsonPrimitive?.contentOrNull,
                        startedAt = o["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        finishedAt = o["finishedAt"]?.jsonPrimitive?.longOrNull,
                    )
                }
                // 任务完成提醒：running → completed/failed/killed 的转变
                val finishedNow = _jobs.value.filter { it.status in setOf("completed", "failed", "killed") }
                finishedNow.forEach { job ->
                    val wasRunning = prev.none { it.id == job.id && it.status == job.status }
                    if (wasRunning) {
                        val icon = when (job.status) {
                            "completed" -> "✅"
                            "failed" -> "❌"
                            else -> "✕"
                        }
                        NotificationHelper.notifyTaskDone(
                            app,
                            "$icon 任务${if (job.status == "completed") "完成" else "结束"}",
                            "${job.label.take(50)}（${job.status}）",
                        )
                    }
                }
            }
            return
        }
        // 实时消息：session/event 帧，且属于当前会话
        if (m == "session/event") {
            val payload = frame["payload"]?.jsonObject
            val sid = payload?.get("sessionId")?.jsonPrimitive?.content
            val evType = payload?.get("event")?.jsonObject?.get("type")?.jsonPrimitive?.content
            android.util.Log.d(TAG, "mux event: sid=$sid cur=${_currentSessionId.value} type=$evType")
            if (sid != null && sid == _currentSessionId.value) {
                val ev = payload["event"]?.jsonObject
                if (ev != null) appendLiveEvent(ev)
                // 任务完成提醒：Agent 回合结束（turn/end）
                if (evType == "turn/end") {
                    val title = _sessions.value.find { it.sessionId == sid }?.title() ?: "会话"
                    NotificationHelper.notifyTaskDone(app, "✅ 任务完成", "「$title」的 Agent 已结束处理")
                }
            }
        }
        // 会话列表防抖刷新
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            delay(1500)
            refresh()
        }
    }

    /** 把一条实时 session 事件追加到消息流（与 history 装配同一套规则）。 */
    private fun appendLiveEvent(ev: JsonObject) {
        val type = ev["type"]?.jsonPrimitive?.content ?: return
        val data = ev["data"]?.jsonObject ?: return
        val time = ev["time"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val list = _messages.value.toMutableList()
        when (type) {
            "user/message" -> {
                val text = joinContentText(data["content"])
                if (!isHarnessNotice(text)) {
                    list += ChatMessage(
                        id = data["id"]?.jsonPrimitive?.content ?: "u-live-${time}",
                        role = "user",
                        text = text,
                        time = time,
                    )
                }
            }
            "assistant/message" -> {
                val text = joinBlockText(data["message"]?.jsonObject?.get("content"), "text")
                if (!isHarnessNotice(text)) {
                    list += ChatMessage(
                        id = "a-live-${time}",
                        role = "assistant",
                        text = text,
                        reasoning = joinBlockText(data["message"]?.jsonObject?.get("content"), "reasoning").ifBlank { null },
                        time = time,
                    )
                }
            }
            "tool/call" -> {
                val name = data["name"]?.jsonPrimitive?.content ?: ""
                if (isInternalTool(name)) return
                val call = ToolCallView(
                    callId = data["callId"]?.jsonPrimitive?.content ?: "c-${time}",
                    name = name,
                    args = data["arguments"]?.jsonPrimitive?.content ?: "",
                )
                attachToolToLastAssistant(list, call)
            }
            "tool/result" -> {
                val src = data["message"]?.jsonObject?.get("source")?.jsonObject
                val callId = src?.get("callId")?.jsonPrimitive?.content
                val result = joinBlockText(data["message"]?.jsonObject?.get("content"), "tool-result")
                if (callId != null) attachToolResultToLastAssistant(list, callId, result)
            }
            else -> return
        }
        _messages.value = list
    }

    private fun attachToolToLastAssistant(list: MutableList<ChatMessage>, call: ToolCallView) {
        if (list.isEmpty()) {
            list += ChatMessage(id = "a-tool-${System.currentTimeMillis()}", role = "assistant", text = "", toolCalls = listOf(call))
            return
        }
        for (i in list.indices.reversed()) {
            if (list[i].role == "assistant") {
                val m = list[i]
                list[i] = m.copy(toolCalls = m.toolCalls + call)
                return
            }
        }
    }

    private fun attachToolResultToLastAssistant(list: MutableList<ChatMessage>, callId: String, result: String) {
        for (i in list.indices.reversed()) {
            val m = list[i]
            val idx = m.toolCalls.indexOfFirst { it.callId == callId }
            if (idx >= 0) {
                val calls = m.toolCalls.toMutableList()
                calls[idx] = calls[idx].also { it.result = result }
                list[i] = m.copy(toolCalls = calls)
                return
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
