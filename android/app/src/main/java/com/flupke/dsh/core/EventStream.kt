package com.flupke.dsh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One downlink WebSocket event stream (e.g. /api/events.mux).
 * Frames are delivered to [onFrame]; when the stream dies, [onClosed] fires
 * so the ConnectionManager can reconnect.
 */
class EventStream(
    private val url: String,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onFrame: (JsonObject) -> Unit,
    private val onClosed: () -> Unit,
) : WebSocketListener() {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, this)
    }

    fun close() {
        runCatching { socket?.close(1000, "app close") }
        socket = null
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching { json.decodeFromString(JsonObject.serializer(), text) }
            .onSuccess { frame -> scope.launch { onFrame(frame) } }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        scope.launch { onClosed() }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        scope.launch { onClosed() }
    }
}
