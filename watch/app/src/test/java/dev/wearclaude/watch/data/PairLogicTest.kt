package dev.wearclaude.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PairLogicTest {
    @Test fun normalizeUrls() {
        assertEquals("http://192.168.1.5:8787", normalizeBridgeUrl("192.168.1.5:8787"))
        assertEquals("https://my-pc.tail1234.ts.net", normalizeBridgeUrl("my-pc.tail1234.ts.net"))
        assertEquals("https://my-pc.tail1234.ts.net", normalizeBridgeUrl("my-pc.tail1234.ts.net/"))
        assertEquals("http://localhost:8787", normalizeBridgeUrl("localhost:8787"))
        assertEquals("http://mypc.local:8787", normalizeBridgeUrl("mypc.local:8787"))
        assertEquals("https://x.ts.net", normalizeBridgeUrl(" https://x.ts.net/ "))
        assertEquals("http://10.0.2.2:8787", normalizeBridgeUrl("http://10.0.2.2:8787//"))
    }

    @Test fun pairErrors() {
        assertEquals("Wrong or expired code", pairErrorMessage(BridgeException(401, "invalid")))
        assertEquals("Wrong or expired code", pairErrorMessage(BridgeException(400, "bad")))
        assertEquals("Too many tries — wait 5 minutes", pairErrorMessage(BridgeException(429, "locked")))
        assertEquals("Can't reach your PC", pairErrorMessage(IOException("x")))
        assertEquals("Pairing failed (500)", pairErrorMessage(BridgeException(500, "x")))
    }

    @Test fun deviceNames() {
        assertEquals("Wear OS watch", deviceName(null))
        assertEquals("Wear OS watch", deviceName("  "))
        assertEquals("Pixel Watch 3", deviceName("Pixel Watch 3"))
        assertEquals(64, deviceName("x".repeat(100)).length)
    }
}
