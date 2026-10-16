package com.comfyapp.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp WebSocket implementation of [ComfyEvents] against `/ws?clientId=...`.
 * Reconnects with a fixed 2s delay until the collector cancels.
 */
class OkHttpComfyEvents(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val baseUrl: () -> String,
) : ComfyEvents {

    override fun events(clientId: String): Flow<ProgressEvent> = callbackFlow {
        var socket: WebSocket? = null
        val reconnectLoop = launch {
            while (isActive) {
                val socketDown = CompletableDeferred<Unit>()
                socket = okHttp.newWebSocket(
                    Request.Builder().url(wsUrl(clientId)).build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            parse(text)?.let { trySend(it) }
                        }

                        // Binary frames (preview JPEGs): ignored.

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            trySend(ProgressEvent.Disconnected)
                            socketDown.complete(Unit)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            trySend(ProgressEvent.Disconnected)
                            socketDown.complete(Unit)
                        }
                    },
                )
                socketDown.await()
                delay(RECONNECT_DELAY_MS)
            }
        }
        awaitClose {
            reconnectLoop.cancel()
            socket?.cancel()
        }
    }

    /** http(s)://host:port → ws(s)://host:port/ws?clientId=... */
    private fun wsUrl(clientId: String): String {
        val base = baseUrl().trimEnd('/')
        val wsBase = if (base.startsWith("https://")) "wss://${base.removePrefix("https://")}"
        else "ws://${base.removePrefix("http://")}"
        return "$wsBase/ws?clientId=$clientId"
    }

    /** Parses one text frame; unknown types / malformed JSON → null (ignored). */
    private fun parse(text: String): ProgressEvent? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val data = obj.obj("data") ?: JsonObject(emptyMap())
        return when (obj.str("type")) {
            "status" -> ProgressEvent.Status(
                queueRemaining = data.obj("status").obj("exec_info").int("queue_remaining"),
            )
            "executing" -> ProgressEvent.Executing(
                nodeId = data.str("node"),
                promptId = data.str("prompt_id"),
            )
            "progress" -> ProgressEvent.Progress(
                value = data.int("value"),
                max = data.int("max"),
                promptId = data.str("prompt_id"),
                nodeId = data.str("node"),
            )
            "executed" -> ProgressEvent.Executed(
                nodeId = data.str("node") ?: "",
                promptId = data.str("prompt_id") ?: "",
                output = data.obj("output") ?: JsonObject(emptyMap()),
            )
            "execution_success" -> ProgressEvent.ExecutionSuccess(promptId = data.str("prompt_id") ?: "")
            "execution_error" -> ProgressEvent.ExecutionError(
                promptId = data.str("prompt_id"),
                message = data.str("exception_message") ?: data.toString(),
            )
            else -> null
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 2_000L
    }
}
