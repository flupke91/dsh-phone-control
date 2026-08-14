package com.flupke.dsh.core

import com.flupke.dsh.data.RpcRequest
import com.flupke.dsh.data.ServerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class DshRpcException(val code: String, message: String) : Exception("$code: $message")

/**
 * Minimal JSON-RPC client for the DSH web `/api` protocol.
 * POST /api/<method> with a `client-request` envelope; returns result.value.
 */
class RpcClient(
    val baseUrl: String, // e.g. http://192.168.x.x:3080
    private val client: OkHttpClient = defaultClient(),
) {
    // encodeDefaults=true is REQUIRED: kotlinx.serialization 1.8+ omits
    // fields equal to their defaults by default, which would drop the
    // envelope's `type`/`payload` and make the DSH server reject it with
    // "bad-request: invalid client-request message".
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun call(method: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = json.encodeToString(
            RpcRequest.serializer(),
            RpcRequest(rpcId = UUID.randomUUID().toString(), method = method, payload = payload),
        )
        val request = Request.Builder()
            .url("$baseUrl/api/$method")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        android.util.Log.d(TAG, "RPC -> $method ($baseUrl/api/$method)")
        // 整个请求+响应体读取必须在 IO 线程：body.string() 若在主线程会触发
        // NetworkOnMainThreadException（大响应体必现）→ 这是"会话不同步"的根因。
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw DshRpcException("http-${resp.code}", resp.message)
                val text = resp.body?.string() ?: throw DshRpcException("empty-body", "empty response body")
                android.util.Log.d(TAG, "RPC <- $method http=${resp.code} bytes=${text.length}")
                val parsed = json.decodeFromString(ServerResponse.serializer(), text)
                if (parsed.result.ok) {
                    android.util.Log.d(TAG, "RPC ok $method value=${parsed.result.value?.toString()?.take(120)}")
                    parsed.result.value ?: JsonObject(emptyMap())
                } else {
                    throw DshRpcException(
                        parsed.result.error?.code ?: "unknown",
                        parsed.result.error?.message ?: "unknown error",
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "dsh-app"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS) // LAN：连不上尽快失败，让"重连/失败"状态快速可见
            .readTimeout(90, TimeUnit.SECONDS) // session.history payloads can be tens of MB
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
