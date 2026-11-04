package dev.wearclaude.watch.data

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventStreamTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun ev(seq: Int) = """{"seq":$seq,"ts":1,"agentId":"a","type":"message","text":"m$seq"}"""

    private fun script(vararg msgs: String) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                msgs.forEach { webSocket.send(it) }
                webSocket.close(1000, null)
            }
        })

    private fun api(token: String = "tok") =
        BridgeApi(server.url("/").toString().trimEnd('/'), token, OkHttpClient())

    @Test fun reconnectsWithSinceFromProviderAndHeaderAuth() = runBlocking {
        server.enqueue(script(ev(5), ev(6)))
        server.enqueue(script(ev(7), ev(8)))
        val stream = EventStream(api(), OkHttpClient(), listOf(10L))
        var last = 4L
        val got = withTimeout(10_000) { stream.events { last }.onEach { last = it.seq }.take(4).toList() }
        assertEquals(listOf(5L, 6L, 7L, 8L), got.map { it.seq })
        val r1 = server.takeRequest(); val r2 = server.takeRequest()
        assertEquals("/events?since=4", r1.path)
        assertEquals("/events?since=6", r2.path)
        assertEquals("Bearer tok", r1.getHeader("Authorization"))
        assertEquals("Bearer tok", r2.getHeader("Authorization"))
    }

    @Test fun helloAndResetAreEmittedUnfiltered() = runBlocking {
        server.enqueue(script("""{"type":"hello","bootId":"b","seq":3}""", """{"type":"reset","bootId":"b","seq":3}""", ev(1)))
        val stream = EventStream(api("t"), OkHttpClient(), listOf(10L))
        val got = withTimeout(10_000) { stream.events { 50 }.take(3).toList() }
        assertEquals(WatchEvent.Hello(bootId = "b", seq = 3), got[0])
        assertEquals(WatchEvent.Reset(seq = 3, bootId = "b"), got[1])
        assertEquals(1L, got[2].seq)
    }

    @Test fun reconnectsAfterFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(script(ev(1)))
        val got = withTimeout(10_000) { EventStream(api("t"), OkHttpClient(), listOf(10L)).events { 0 }.take(1).toList() }
        assertEquals(1L, got.single().seq)
    }

    @Test fun handshake401StopsReconnecting() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(script(ev(1)))
        val ex = try {
            withTimeout(10_000) { EventStream(api(), OkHttpClient(), listOf(10L)).events { 0 }.toList() }
            null
        } catch (e: AuthFailedException) { e }
        assertNotNull(ex)
        kotlinx.coroutines.delay(200)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingCollectorClosesSocket() = runBlocking {
        val closed = java.util.concurrent.CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { webSocket.send(ev(1)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { closed.countDown() }
        }))
        EventStream(api(), OkHttpClient(), listOf(10L)).events { 0 }.take(1).toList()
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
