package dev.wearclaude.watch.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@OptIn(ExperimentalSerializationApi::class)
val BridgeJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    explicitNulls = false
}

/** Picks the subclass by the "type" field; anything unrecognised becomes [WatchEvent.Unknown]. */
object WatchEventSerializer : JsonContentPolymorphicSerializer<WatchEvent>(WatchEvent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WatchEvent> {
        val type = (element as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return when (type) {
            "status" -> WatchEvent.Status.serializer()
            "step" -> WatchEvent.Step.serializer()
            "message" -> WatchEvent.Message.serializer()
            "summary" -> WatchEvent.Summary.serializer()
            "approval_requested" -> WatchEvent.ApprovalRequested.serializer()
            "approval_resolved" -> WatchEvent.ApprovalResolved.serializer()
            "agent_created" -> WatchEvent.AgentCreated.serializer()
            "reset" -> WatchEvent.Reset.serializer()
            "hello" -> WatchEvent.Hello.serializer()
            "chat_item" -> WatchEvent.ChatItemEvent.serializer()
            "chat_status" -> WatchEvent.ChatStatusEvent.serializer()
            else -> WatchEvent.Unknown.serializer()
        }
    }
}

@Serializable(with = WatchEventSerializer::class)
sealed class WatchEvent {
    abstract val seq: Long
    abstract val ts: Long
    abstract val agentId: String

    @Serializable @SerialName("status")
    data class Status(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val status: String, val reason: String? = null,
    ) : WatchEvent()

    @Serializable @SerialName("step")
    data class Step(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val text: String, val depth: Int = 0,
    ) : WatchEvent()

    @Serializable @SerialName("message")
    data class Message(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val text: String,
    ) : WatchEvent()

    @Serializable @SerialName("summary")
    data class Summary(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val text: String, val costUsd: Double? = null,
    ) : WatchEvent()

    @Serializable @SerialName("approval_requested")
    data class ApprovalRequested(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val approvalId: String, val tool: String, val summary: String, val expiresAt: Long,
    ) : WatchEvent()

    @Serializable @SerialName("approval_resolved")
    data class ApprovalResolved(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val approvalId: String, val decision: String,
    ) : WatchEvent()

    @Serializable @SerialName("agent_created")
    data class AgentCreated(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val title: String, val projectId: String,
    ) : WatchEvent()

    /**
     * Control message: the requested `since` could not be replayed. [seq] is the bridge's latest seq and
     * [bootId] its boot id (both absent on old bridges). Handled by the repository, not the reducer.
     */
    @Serializable @SerialName("reset")
    data class Reset(
        override val seq: Long = 0, override val ts: Long = 0, override val agentId: String = "",
        val bootId: String? = null,
    ) : WatchEvent()

    /** Control message sent first on every connection: the bridge's boot id and latest seq. */
    @Serializable @SerialName("hello")
    data class Hello(
        val bootId: String? = null,
        override val seq: Long = 0, override val ts: Long = 0, override val agentId: String = "",
    ) : WatchEvent()

    /** A new item in a PC chat; [agentId] is "pc:<sessionId>". */
    @Serializable @SerialName("chat_item")
    data class ChatItemEvent(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val kind: String, val text: String,
    ) : WatchEvent()

    @Serializable @SerialName("chat_status")
    data class ChatStatusEvent(
        override val seq: Long, override val ts: Long, override val agentId: String,
        val status: String, val title: String, val projectName: String,
        /** For idle: true when the turn ended; false when the bridge timed out waiting for output. */
        val ended: Boolean = false,
        val archived: Boolean = false,
    ) : WatchEvent()

    @Serializable @SerialName("unknown")
    data class Unknown(
        override val seq: Long = 0, override val ts: Long = 0, override val agentId: String = "",
    ) : WatchEvent()
}

@Serializable
data class AgentInfo(
    val id: String,
    val title: String,
    val projectId: String,
    val status: String,
    val lastStep: String? = null,
    val summary: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String? = null,
)

@Serializable
data class ProjectInfo(val id: String, val name: String)

@Serializable
data class PendingApproval(
    val approvalId: String,
    val agentId: String,
    val tool: String,
    val summary: String,
    val expiresAt: Long,
)

@Serializable
data class AgentDetail(
    val agent: AgentInfo,
    val events: List<WatchEvent> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(),
)

@Serializable
data class ChatInfo(
    val id: String,
    val title: String,
    val projectName: String,
    val lastActivity: Long,
    val status: String,
    /** Set from chat_status events; true when the last idle came from a finished turn. */
    val ended: Boolean = false,
    /** True when the desktop chat has a title, so a reply can be relayed into it. */
    val titled: Boolean = false,
    /** True when the chat is archived in the desktop sidebar. */
    val archived: Boolean = false,
)

@Serializable
data class ChatItem(val kind: String, val text: String, val ts: Long)

@Serializable
data class ChatDetail(val chat: ChatInfo, val items: List<ChatItem> = emptyList())
