package dev.wearclaude.watch.data

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun decodesKnownTypes() {
        val e = BridgeJson.decodeFromString<WatchEvent>(
            """{"seq":3,"ts":9,"agentId":"a","type":"approval_requested","approvalId":"x","tool":"Bash","summary":"s","expiresAt":5,"extra":1}""")
        assertEquals(WatchEvent.ApprovalRequested(3, 9, "a", "x", "Bash", "s", 5), e)
        val st = BridgeJson.decodeFromString<WatchEvent>("""{"seq":1,"ts":1,"agentId":"a","type":"status","status":"failed","reason":"boom"}""")
        assertEquals(WatchEvent.Status(1, 1, "a", "failed", "boom"), st)
        val su = BridgeJson.decodeFromString<WatchEvent>("""{"seq":1,"ts":1,"agentId":"a","type":"summary","text":"t"}""")
        assertEquals(WatchEvent.Summary(1, 1, "a", "t", null), su)
    }

    @Test fun decodesChatEvents() {
        val i = BridgeJson.decodeFromString(WatchEventSerializer, """{"type":"chat_item","seq":3,"ts":9,"agentId":"pc:s","kind":"reply","text":"x"}""")
        assertEquals(WatchEvent.ChatItemEvent(3, 9, "pc:s", "reply", "x"), i)
        val st = BridgeJson.decodeFromString(WatchEventSerializer, """{"type":"chat_status","seq":4,"ts":9,"agentId":"pc:s","status":"idle","title":"T","projectName":"P"}""")
        assertEquals(WatchEvent.ChatStatusEvent(4, 9, "pc:s", "idle", "T", "P"), st)
        assertFalse((st as WatchEvent.ChatStatusEvent).ended)
        assertFalse(st.archived)
        val arch = BridgeJson.decodeFromString(WatchEventSerializer, """{"type":"chat_status","seq":6,"ts":9,"agentId":"pc:s","status":"idle","title":"T","projectName":"P","archived":true}""")
        assertTrue((arch as WatchEvent.ChatStatusEvent).archived)
        val ended = BridgeJson.decodeFromString(WatchEventSerializer, """{"type":"chat_status","seq":5,"ts":9,"agentId":"pc:s","status":"idle","ended":true,"title":"T","projectName":"P"}""")
        assertTrue((ended as WatchEvent.ChatStatusEvent).ended)
    }

    @Test fun resetWithoutSeq() {
        assertTrue(BridgeJson.decodeFromString<WatchEvent>("""{"type":"reset"}""") is WatchEvent.Reset)
    }

    @Test fun helloAndResetCarryBootIdAndSeq() {
        assertEquals(WatchEvent.Hello(bootId = "b1", seq = 7),
            BridgeJson.decodeFromString<WatchEvent>("""{"type":"hello","bootId":"b1","seq":7}"""))
        assertEquals(WatchEvent.Reset(seq = 9, bootId = "b1"),
            BridgeJson.decodeFromString<WatchEvent>("""{"type":"reset","bootId":"b1","seq":9}"""))
    }

    @Test fun unknownType() {
        val e = BridgeJson.decodeFromString<WatchEvent>("""{"seq":4,"ts":1,"agentId":"a","type":"future_thing","foo":"bar"}""")
        assertEquals(WatchEvent.Unknown(4, 1, "a"), e)
    }

    @Test fun agentInfoOptionalFields() {
        val a = BridgeJson.decodeFromString<AgentInfo>(
            """{"id":"a","title":"t","projectId":"p","status":"idle","createdAt":1,"updatedAt":2,"sessionId":null}""")
        assertNull(a.lastStep); assertNull(a.summary)
    }
}
