package dev.wearclaude.watch.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BridgeApiTest {
    private lateinit var server: MockWebServer
    private fun api(token: String? = "tok") =
        BridgeApi(server.url("/").toString().trimEnd('/'), token, OkHttpClient())

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun sendsBearerHeaderAndParsesAgents() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"a","title":"t","projectId":"p","status":"idle","createdAt":1,"updatedAt":2,"sessionId":"s"}]""")
            .setHeader("X-Bridge-Boot", "boot-9").setHeader("X-Bridge-Seq", "42"))
        val snap = api().agents()
        assertEquals("a", snap.agents.single().id)
        assertEquals("boot-9", snap.bootId)
        assertEquals(42L, snap.seq)
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("/agents", req.path)
    }

    @Test fun chatsParse() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"s1","title":"T","projectName":"P","lastActivity":123,"status":"working"}]"""))
        val c = api().chats().single()
        assertEquals(ChatInfo("s1", "T", "P", 123, "working"), c)
        assertFalse(c.archived)
        assertEquals("/chats", server.takeRequest().path)
    }

    @Test fun chats404IsEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"disabled"}"""))
        assertTrue(api().chats().isEmpty())
    }

    @Test fun chats401Throws() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"no"}"""))
        val ex = try { api().chats(); null } catch (e: BridgeException) { e }
        assertEquals(401, ex!!.code)
    }

    @Test fun chatDetailParses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"chat":{"id":"s1","title":"T","projectName":"P","lastActivity":5,"status":"idle"},"items":[{"kind":"prompt","text":"hi","ts":1},{"kind":"reply","text":"yo","ts":2}]}"""))
        val d = api().chat("s1")
        assertEquals("s1", d.chat.id)
        assertEquals(listOf("hi", "yo"), d.items.map { it.text })
        assertEquals("/chats/s1", server.takeRequest().path)
    }

    @Test fun chat404Throws() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"nf"}"""))
        val ex = try { api().chat("zz"); null } catch (e: BridgeException) { e }
        assertEquals(404, ex!!.code)
    }

    @Test fun agentsWithoutHeadersDefaults() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        val snap = api().agents()
        assertNull(snap.bootId)
        assertEquals(0L, snap.seq)
    }

    @Test fun conflictMapsToBridgeException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"busy"}"""))
        val ex = try { api().followUp("a", "hi"); null } catch (e: BridgeException) { e }
        assertNotNull(ex)
        assertEquals(409, ex!!.code)
        assertEquals("busy", ex.message)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/agents/a/messages", req.path)
        assertEquals("""{"prompt":"hi"}""", req.body.readUtf8())
    }

    @Test fun pairParsesTokenWithoutAuth() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"abc"}"""))
        assertEquals("abc", api(null).pair("123456", "Watch"))
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertEquals("/pair", req.path)
        assertEquals("""{"code":"123456","deviceName":"Watch"}""", req.body.readUtf8())
    }

    @Test fun pairLockedErrorKeepsCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"locked"}"""))
        val ex = try { api(null).pair("1", "w"); null } catch (e: BridgeException) { e }
        assertEquals(429, ex!!.code); assertEquals("locked", ex.message)
    }

    @Test fun agentDetailAndDecide() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"agent":{"id":"a","title":"t","projectId":"p","status":"running","createdAt":1,"updatedAt":2,"sessionId":"s"},"events":[{"seq":1,"ts":1,"agentId":"a","type":"step","text":"x","depth":0}],"approvals":[{"approvalId":"ap","agentId":"a","tool":"Bash","summary":"s","expiresAt":9}]}"""))
        val d = api().agent("a")
        assertEquals(1, d.events.size); assertEquals("ap", d.approvals.single().approvalId)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        api().decide("ap", false)
        server.takeRequest()
        val r = server.takeRequest()
        assertEquals("/approvals/ap", r.path)
        assertEquals("""{"decision":"deny"}""", r.body.readUtf8())
    }

    @Test fun createAgentAndProjectsAndStop() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"agentId":"n1"}"""))
        assertEquals("n1", api().createAgent("p", "do it"))
        server.enqueue(MockResponse().setBody("""[{"id":"p","name":"Proj"}]"""))
        assertEquals("Proj", api().projects().single().name)
        server.enqueue(MockResponse().setBody("{}"))
        api().stop("a")
        server.takeRequest(); server.takeRequest()
        assertEquals("/agents/a/stop", server.takeRequest().path)
    }

    @Test fun sendToChatPostsPromptWithLongTimeout() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
        val short = OkHttpClient.Builder().readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        BridgeApi(server.url("/").toString().trimEnd('/'), "tok", short).sendToChat("s1", "hi there")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/chats/s1/messages", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("""{"prompt":"hi there"}""", req.body.readUtf8())
    }

    @Test fun sendToChatConflictCarriesErrorCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"not_live"}"""))
        val ex = try { api().sendToChat("s1", "x"); null } catch (e: BridgeException) { e }
        assertEquals(409, ex!!.code)
        assertEquals("not_live", ex.message)
    }

    @Test fun chatInfoTitledDefaultsFalse() {
        assertFalse(BridgeJson.decodeFromString(ChatInfo.serializer(), """{"id":"a","title":"t","projectName":"p","lastActivity":1,"status":"idle"}""").titled)
        assertTrue(BridgeJson.decodeFromString(ChatInfo.serializer(), """{"id":"a","title":"t","projectName":"p","lastActivity":1,"status":"idle","titled":true}""").titled)
    }
}
