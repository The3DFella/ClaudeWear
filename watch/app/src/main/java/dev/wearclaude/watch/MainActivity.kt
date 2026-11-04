package dev.wearclaude.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import dev.wearclaude.watch.service.EXTRA_OPEN_ROUTE
import dev.wearclaude.watch.service.isBusy
import dev.wearclaude.watch.service.routeFromExtras
import dev.wearclaude.watch.service.startWatchService
import dev.wearclaude.watch.service.watchedState
import dev.wearclaude.watch.ui.AppNav
import dev.wearclaude.watch.ui.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingRoute = routeFromExtras(intent?.getStringExtra(EXTRA_OPEN_ROUTE))
            intent?.removeExtra(EXTRA_OPEN_ROUTE)
        }

        // Start the service while the app is open and any watch agent or PC chat is busy (it delivers finish buzzes).
        val controller = (application as WearClaudeApp).controller
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.watchedState().filterNotNull()
                    .map { isBusy(it) }
                    .distinctUntilChanged()
                    .collect { if (it) startWatchService(this@MainActivity) }
            }
        }

        setContent {
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            MaterialTheme {
                AppScaffold { AppNav(vm, pendingRoute) { pendingRoute = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromExtras(intent.getStringExtra(EXTRA_OPEN_ROUTE))?.let { pendingRoute = it }
        intent.removeExtra(EXTRA_OPEN_ROUTE)
    }
}
