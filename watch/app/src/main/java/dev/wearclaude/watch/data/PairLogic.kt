package dev.wearclaude.watch.data

import java.io.IOException

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

fun normalizeBridgeUrl(input: String): String {
    val t = input.trim().trimEnd('/')
    if (t.contains("://")) return t
    val host = t.substringBefore('/').substringBefore(':').lowercase()
    val local = IPV4.matches(host) || host == "localhost" || host.endsWith(".local")
    return (if (local) "http://" else "https://") + t
}

fun pairErrorMessage(t: Throwable): String = when {
    t is BridgeException && (t.code == 401 || t.code == 400) -> "Wrong or expired code"
    t is BridgeException && t.code == 429 -> "Too many tries — wait 5 minutes"
    t is BridgeException -> "Pairing failed (${t.code})"
    t is IOException -> "Can't reach your PC"
    else -> "Pairing failed"
}

fun deviceName(model: String?): String =
    model?.trim().orEmpty().take(64).ifBlank { "Wear OS watch" }
