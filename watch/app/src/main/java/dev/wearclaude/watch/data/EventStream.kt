package dev.wearclaude.watch.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

fun interface EventSource {
    /** [since] is read on every (re)connect, so the caller decides where to resume. */
    fun events(since: () -> Long): Flow<WatchEvent>
}

/**
 * Cold flow over the bridge WebSocket. Reconnects forever with backoff
 * ([backoffMs], last value repeats), resuming from `since()` each time.
 * Emits everything it decodes, including hello/reset; dedup is the repository's job.
 */
class AuthFailedException : Exception("unauthorized")

class EventStream(
    private val api: BridgeApi,
    private val client: OkHttpClient,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
) : EventSource {

    private sealed interface Item {
        data class Msg(val text: String) : Item
        data object Closed : Item
        data object AuthFailed : Item
    }

    override fun events(since: () -> Long): Flow<WatchEvent> = flow {
        var attempt = 0
        while (true) {
            val ch = Channel<Item>(Channel.UNLIMITED)
            val ws = client.newWebSocket(request(since()), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { ch.trySend(Item.Msg(text)) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    ch.trySend(Item.Closed)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ch.trySend(Item.Closed) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ch.trySend(if (response?.code == 401) Item.AuthFailed else Item.Closed)
                }
            })
            try {
                for (item in ch) {
                    if (item is Item.AuthFailed) throw AuthFailedException()
                    if (item is Item.Closed) break
                    val event = try {
                        BridgeJson.decodeFromString(WatchEvent.serializer(), (item as Item.Msg).text)
                    } catch (_: Exception) {
                        continue
                    }
                    attempt = 0
                    emit(event)
                }
            } finally {
                ws.cancel()
            }
            delay(backoffMs[minOf(attempt, backoffMs.size - 1)])
            attempt++
        }
    }

    private fun request(since: Long): Request {
        val url = api.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("events")
            .addQueryParameter("since", since.toString())
            .build()
        return Request.Builder().url(url).header("Authorization", "Bearer ${api.token.orEmpty()}").build()
    }
}
