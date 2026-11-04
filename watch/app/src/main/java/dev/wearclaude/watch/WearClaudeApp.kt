package dev.wearclaude.watch

import android.app.Application
import android.os.Build
import dev.wearclaude.watch.data.AgentRepository
import dev.wearclaude.watch.data.BridgeApi
import dev.wearclaude.watch.data.EventStream
import dev.wearclaude.watch.data.Settings
import dev.wearclaude.watch.data.deviceName
import dev.wearclaude.watch.service.StartingCommands
import dev.wearclaude.watch.service.startWatchService
import dev.wearclaude.watch.ui.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Owns the single [AppController] shared by the UI and the watch service. */
class WearClaudeApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // Pings keep NAT/proxy mappings alive and detect dead sockets (the bridge pings every 30 s too).
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        controller = AppController(
            store = Settings(this),
            pairer = { url, code, name -> BridgeApi(url, null, client).pair(code, name) },
            repoFactory = { url, token ->
                val api = BridgeApi(url, token, client)
                AgentRepository(api, EventStream(api, client), scope)
            },
            scope = scope,
            deviceName = deviceName(Build.MODEL),
            commandsFactory = { url, token -> StartingCommands(BridgeApi(url, token, client)) { startWatchService(this) } },
        )
    }
}
