package com.flupke.dsh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Client→server RPC envelope, identical to the DSH web client protocol. */
@Serializable
data class RpcRequest(
    val type: String = "client-request",
    val rpcId: String,
    val method: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RpcError(
    val code: String = "",
    val message: String = "",
    val details: JsonObject? = null,
)

@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonObject? = null,
    val error: RpcError? = null,
)

/** Server→client response envelope. */
@Serializable
data class ServerResponse(
    val type: String = "server-response",
    val rpcId: String = "",
    val result: RpcResult,
)

/** One row of `session.list` (fields observed from the real API). */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val parentSessionId: String? = null,
    val projections: SessionProjections? = null,
)

@Serializable
data class SessionProjections(
    val asOfSeq: Long = 0,
    val values: JsonObject = JsonObject(emptyMap()),
)

/**
 * The session title. Server shapes differ: sometimes an object
 * {"ver":1,"seq":N,"val":"..."}, sometimes a bare string — handle both.
 */
fun SessionSummary.title(): String? {
    val t = projections?.values?.get("title") ?: return null
    return when (t) {
        is kotlinx.serialization.json.JsonObject -> t["val"]?.jsonPrimitive?.content
        is kotlinx.serialization.json.JsonPrimitive -> t.content
        else -> null
    }
}

/** Workspace row of `workspace.list`. */
@Serializable
data class WorkspaceView(
    val workspaceId: String,
    val path: String,
    val title: String = "",
    val sessionIds: List<String> = emptyList(),
)

/** 一次工具调用（来自 history 的 tool/call + tool/result 事件）。 */
data class ToolCallView(
    var callId: String,
    var name: String,
    var args: String,
    var result: String? = null,
)

/** 一条已装配的消息（user / assistant，含思考块与工具调用）。 */
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallView> = emptyList(),
    val time: Long = 0,
)

/** 队列条目（来自 session/queue 帧，与 Web 端 queue-mirror 对齐）。 */
data class QueueItem(
    val id: String,
    val messageId: String,
    val placement: String, // queued | steering
    val preview: String,
    val text: String,
)

/** 后台任务（来自 session/jobs 帧，taskViewSchema）。 */
data class JobView(
    val id: String,
    val kind: String,
    val label: String,
    val status: String, // running | stopping | completed | killed | failed
    val detail: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

/** One model group of `session.models` (provider-scoped catalog). */
data class ModelGroup(val id: String, val name: String, val models: List<String>)

/** One row of `pluginInventory/list` (entry id, module name, enabled). */
data class PluginEntry(val entryId: String, val moduleName: String, val enabled: Boolean)
