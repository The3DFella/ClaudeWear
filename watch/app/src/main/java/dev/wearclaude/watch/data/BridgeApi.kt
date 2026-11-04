package dev.wearclaude.watch.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

const val CHAT_SEND_TIMEOUT_S = 100L

class BridgeException(val code: Int, message: String) : Exception(message)

/** GET /agents: the list plus the bridge boot id and the event seq the list is current up to. */
data class AgentsSnapshot(val agents: List<AgentInfo>, val bootId: String?, val seq: Long)

/** The slice of the API the repository depends on (eases faking in tests). */
interface BridgeApiContract {
    suspend fun agents(): AgentsSnapshot
    suspend fun agent(id: String): AgentDetail
    suspend fun chats(): List<ChatInfo>
    suspend fun chat(id: String): ChatDetail
}

class BridgeApi(
    val baseUrl: String,
    val token: String?,
    private val client: OkHttpClient,
) : BridgeApiContract, AgentCommands {
    private val base = baseUrl.trimEnd('/')
    private val jsonType = "application/json".toMediaType()

    @Serializable private data class PairBody(val code: String, val deviceName: String)
    @Serializable private data class PairResult(val token: String)
    @Serializable private data class CreateBody(val projectId: String, val prompt: String)
    @Serializable private data class CreateResult(val agentId: String)
    @Serializable private data class PromptBody(val prompt: String)
    @Serializable private data class DecisionBody(val decision: String)

    suspend fun pair(code: String, deviceName: String): String =
        post("/pair", BridgeJson.encodeToString(PairBody.serializer(), PairBody(code, deviceName)))
            .let { BridgeJson.decodeFromString(PairResult.serializer(), it).token }

    override suspend fun projects(): List<ProjectInfo> =
        BridgeJson.decodeFromString(ListSerializer(ProjectInfo.serializer()), get("/projects"))

    override suspend fun agents(): AgentsSnapshot {
        val (body, headers) = executeWithHeaders(build("/agents").get().build())
        return AgentsSnapshot(
            agents = BridgeJson.decodeFromString(ListSerializer(AgentInfo.serializer()), body),
            bootId = headers["X-Bridge-Boot"],
            seq = headers["X-Bridge-Seq"]?.toLongOrNull() ?: 0L,
        )
    }

    override suspend fun agent(id: String): AgentDetail =
        BridgeJson.decodeFromString(AgentDetail.serializer(), get("/agents/$id"))

    /** 404 means the PC-chats feature is disabled on the bridge: treated as an empty list. */
    override suspend fun chats(): List<ChatInfo> = try {
        BridgeJson.decodeFromString(ListSerializer(ChatInfo.serializer()), get("/chats"))
    } catch (e: BridgeException) {
        if (e.code == 404) emptyList() else throw e
    }

    override suspend fun chat(id: String): ChatDetail =
        BridgeJson.decodeFromString(ChatDetail.serializer(), get("/chats/$id"))

    override suspend fun createAgent(projectId: String, prompt: String): String =
        post("/agents", BridgeJson.encodeToString(CreateBody.serializer(), CreateBody(projectId, prompt)))
            .let { BridgeJson.decodeFromString(CreateResult.serializer(), it).agentId }

    override suspend fun followUp(id: String, prompt: String) {
        post("/agents/$id/messages", BridgeJson.encodeToString(PromptBody.serializer(), PromptBody(prompt)))
    }

    override suspend fun stop(id: String) {
        post("/agents/$id/stop", "{}")
    }

    override suspend fun decide(approvalId: String, allow: Boolean) {
        post("/approvals/$approvalId",
            BridgeJson.encodeToString(DecisionBody.serializer(), DecisionBody(if (allow) "allow" else "deny")))
    }

    override suspend fun sendToChat(id: String, prompt: String) {
        val req = build("/chats/${java.net.URLEncoder.encode(id, "UTF-8")}/messages")
            .post(BridgeJson.encodeToString(PromptBody.serializer(), PromptBody(prompt)).toRequestBody(jsonType)).build()
        // The bridge may hold the request for ~90 s while it relays into the desktop app.
        executeWithHeaders(req, client.newBuilder().readTimeout(CHAT_SEND_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build())
    }

    private suspend fun get(path: String): String =
        execute(build(path).get().build())

    private suspend fun post(path: String, json: String): String =
        execute(build(path).post(json.toRequestBody(jsonType)).build())

    private fun build(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        if (token != null && path != "/pair") b.header("Authorization", "Bearer $token")
        return b
    }

    private suspend fun execute(request: Request): String = executeWithHeaders(request).first

    private suspend fun executeWithHeaders(request: Request, http: OkHttpClient = client): Pair<String, Headers> = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        val response = call.await()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw BridgeException(it.code, errorMessage(body, it))
            body to it.headers
        }
    }

    private fun errorMessage(body: String, r: Response): String = try {
        ((BridgeJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    } ?: r.message.ifEmpty { "HTTP ${r.code}" }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
