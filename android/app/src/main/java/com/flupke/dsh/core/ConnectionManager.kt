package com.flupke.dsh.core

import com.flupke.dsh.data.ModelGroup
import com.flupke.dsh.data.PluginEntry
import com.flupke.dsh.data.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * Owns the connection to one DSH host: handshake (host.describe), the two
 * downlink WebSocket streams (mux + host), and exponential-backoff reconnect.
 * Invalid URLs fail fast into [ConnectionState.Failed]; every failure carries
 * a human-readable reason so the UI can show what is wrong.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val client: OkHttpClient = RpcClient.defaultClient(),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile var baseUrl: String? = null
        private set
    @Volatile private var rpc: RpcClient? = null
    private var job: Job? = null
    private var mux: EventStream? = null
    private var host: EventStream? = null

    private val maxAttempts = 5

    companion object {
        /** Normalize user input: add the scheme, trim the trailing slash. */
        fun normalizeBaseUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }

    /** Connect (or reconnect) to a host. [onEvent] receives every mux frame. */
    fun connect(rawUrl: String, onEvent: (JsonObject) -> Unit = {}) {
        disconnect()
        val normalized = normalizeBaseUrl(rawUrl)
        if (normalized.toHttpUrlOrNull() == null) {
            _state.value = ConnectionState.Failed("地址格式不正确：$rawUrl")
            return
        }
        baseUrl = normalized
        rpc = RpcClient(baseUrl!!, client)
        job = scope.launch {
            var attempt = 0
            var lastError = ""
            var everConnected = false
            while (isActive) {
                attempt++
                _state.value = if (attempt == 1) ConnectionState.Connecting(1) else ConnectionState.Reconnecting(attempt, lastError)
                val died = CompletableDeferred<Unit>()
                try {
                    rpc!!.call("host.describe") // readiness handshake
                    val wsBase = baseUrl!!.replaceFirst("http", "ws").replaceFirst("https", "wss")
                    mux = EventStream("$wsBase/api/events.mux", client, this, onEvent) { died.complete(Unit) }
                    host = EventStream("$wsBase/api/events.host", client, this, {}) { }
                    mux!!.connect()
                    host!!.connect()
                    _state.value = ConnectionState.Connected
                    everConnected = true
                    attempt = 0
                    died.await() // block until the mux stream dies
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    mux?.close(); mux = null
                    host?.close(); host = null
                }
                if (!isActive) break
                if (everConnected) {
                    attempt = 1
                    lastError = "连接已断开，正在重连"
                    _state.value = ConnectionState.Reconnecting(1, lastError)
                    delay(backoffMs(1))
                    continue
                }
                if (attempt >= maxAttempts) {
                    _state.value = ConnectionState.Failed(lastError.ifBlank { "无法连接 $baseUrl" })
                    break
                }
                _state.value = ConnectionState.Reconnecting(attempt + 1, lastError)
                delay(backoffMs(attempt))
            }
            if (!isActive) _state.value = ConnectionState.Disconnected
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        mux?.close(); mux = null
        host?.close(); host = null
        _state.value = ConnectionState.Disconnected
    }

    suspend fun call(method: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val r = rpc ?: throw DshRpcException("not-connected", "no host configured")
        return r.call(method, payload)
    }

    /**
     * 拉取会话历史并**只保留装配所需的事件**（user/message、assistant/message、
     * tool/call、tool/result），丢弃逐 token 的 chunk 洪流——否则 7MB+ 的原始
     * 事件流无法直接渲染。
     */
    suspend fun history(sessionId: String): List<JsonObject> {
        val value = call("session.history", buildJsonObject {
            put("sessionId", sessionId)
            put("limit", 500)
        })
        val events = value["events"]?.jsonArray ?: return emptyList()
        val keep = setOf("user/message", "assistant/message", "tool/call", "tool/result")
        return events.mapNotNull { e ->
            val ev = e.jsonObject["event"]?.jsonObject ?: e.jsonObject
            val type = ev["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (type in keep) ev else null
        }
    }

    /**
     * 发送一条消息给 DSH Agent。
     * @param steer true=打断当前执行立即注入（mode=steer）；false=排队（mode=queue）。
     */
    suspend fun prompt(sessionId: String, text: String, steer: Boolean = false) {
        call("session.prompt", buildJsonObject {
            put("sessionId", sessionId)
            put("mode", if (steer) "steer" else "queue")
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
            put("clientTimeZone", "Asia/Shanghai")
        })
    }

    /** 停止当前会话正在执行的回合。 */
    suspend fun cancelSession(sessionId: String) {
        call("session.cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    /** 对排队中的消息执行操作：steer（立即打断执行）/ remove（移除）。 */
    suspend fun updateQueue(sessionId: String, itemId: String, action: String) {
        call("session.updateQueue", buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", kotlinx.serialization.json.buildJsonObject {
                put("kind", action)
            })
        })
    }

    suspend fun listSessions(): List<SessionSummary> {
        val value = call("session.list")
        val items = value["items"]?.jsonArray ?: return emptyList()
        return items.map { json.decodeFromString(SessionSummary.serializer(), it.toString()) }
    }

    /** Create a session with the host defaults; returns the new sessionId. */
    suspend fun createSession(): String {
        val value = call("session.create", buildJsonObject { })
        return value["sessionId"]?.jsonPrimitive?.content
            ?: throw DshRpcException("no-session-id", "session.create 未返回 sessionId")
    }

    suspend fun renameSession(sessionId: String, title: String) {
        call("session.rename", buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        })
    }

    /** Delete = archive (DSH 无 session.delete，使用 workspace.archiveSession). */
    suspend fun archiveSession(sessionId: String) {
        call("workspace.archiveSession", buildJsonObject { put("sessionId", sessionId) })
    }

    /**
     * 已归档会话集合（workspace.list 响应的 archivedSessionIds）。
     * 与 Web 端 sessionVisible 逻辑一致：已归档的会话在 web 上不可见。
     */
    suspend fun archivedSessionIds(): Set<String> {
        val value = call("workspace.list")
        return (value["archivedSessionIds"]?.jsonArray ?: emptyList())
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .toSet()
    }

    /** 一键诊断：逐项检测连接链路，返回每步结果（供连接页展示）。 */
    suspend fun diagnose(url: String): List<Pair<String, Boolean>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val rc = RpcClient(url)
        val steps = mutableListOf<Pair<String, Boolean>>()
        // 1. TCP 连通
        val host = url.replace("http://", "").replace("https://", "").substringBefore(":")
        val port = url.substringAfter(":", "").substringBefore("/").toIntOrNull() ?: 3080
        try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(host, port), 4000)
            sock.close()
            steps += "① 网络连通（$host:$port）" to true
        } catch (e: Exception) {
            steps += "① 网络连通：失败（连不上 $host:$port，手机与电脑不在同一网络或防火墙拦截）" to false
            return@withContext steps
        }
        // 2. host.describe（围栏/服务）
        runCatching { rc.call("host.describe") }
            .onSuccess { steps += "② 服务与围栏（host.describe）" to true }
            .onFailure { e ->
                steps += "② 服务与围栏：失败（${e.message}——403 表示电脑端围栏未放行该手机 IP）" to false
                return@withContext steps
            }
        // 3. workspace.list
        runCatching { rc.call("workspace.list") }
            .onSuccess { v ->
                val ids = (v["items"]?.jsonArray ?: emptyList()).mapNotNull { it.jsonObject["sessionIds"]?.jsonArray }.flatten()
                steps += "③ 工作区读取（${ids.size} 个会话）" to true
            }
            .onFailure { e ->
                steps += "③ 工作区读取：失败（${e.message}）" to false
                return@withContext steps
            }
        // 4. session.list（工作区过滤后数量）
        runCatching { rc.call("session.list") }
            .onSuccess { v ->
                steps += "④ 会话同步（服务端返回 ${(v["items"]?.jsonArray ?: emptyList()).size} 个，App 仅显示工作区内的）" to true
            }
            .onFailure { e ->
                steps += "④ 会话同步：失败（${e.message}）" to false
            }
        steps
    }

    /** Current model + provider-grouped catalog for a session. */
    suspend fun sessionModels(sessionId: String): Pair<String?, List<ModelGroup>> {
        val value = call("session.models", buildJsonObject { put("sessionId", sessionId) })
        val current = value["current"]?.jsonObject?.get("model")?.jsonPrimitive?.content
        val groups = value["groups"]?.jsonArray?.mapNotNull { g ->
            val o = g.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: ""
            val name = o["name"]?.jsonPrimitive?.content ?: id
            val models = o["models"]?.jsonArray?.mapNotNull { m ->
                m.jsonObject["model"]?.jsonPrimitive?.content
                    ?: m.jsonObject["id"]?.jsonPrimitive?.content
            } ?: emptyList()
            ModelGroup(id, name, models)
        } ?: emptyList()
        return current to groups
    }

    suspend fun selectModel(sessionId: String, provider: String, model: String) {
        call("session.selectModel", buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
        })
    }

    /** Host Loader 插件清单（Remote: /api/pluginInventory/list，payload {"args":{}}）. */
    suspend fun listPlugins(): List<PluginEntry> {
        val value = call("pluginInventory/list", buildJsonObject { put("args", JsonObject(emptyMap())) })
        val entries = value["entries"]?.jsonArray ?: return emptyList()
        return entries.mapNotNull { e ->
            val o = e.jsonObject
            val entryId = o["entryId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            PluginEntry(
                entryId = entryId,
                moduleName = o["moduleName"]?.jsonPrimitive?.content ?: "",
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = 500L * (1L shl (attempt - 1).coerceIn(0, 5))
        return base.coerceAtMost(10_000L)
    }
}
