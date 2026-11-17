package net.pdd.comagic.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.pdd.comagic.mobile.ui.ChatScreen
import net.pdd.comagic.mobile.ui.DirectoryPickerScreen
import net.pdd.comagic.mobile.ui.ComagicTheme
import net.pdd.comagic.mobile.ui.PairingScreen
import net.pdd.comagic.mobile.ui.ServersScreen
import net.pdd.comagic.mobile.ui.SessionListScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ComagicTheme {
                val viewModel: AppViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                Root(state, viewModel)
            }
        }
    }
}

@Composable
private fun Root(state: UiState, viewModel: AppViewModel) {
    var serversOpen by rememberSaveable { mutableStateOf(false) }
    var serverEditor by remember { mutableStateOf<Endpoint?>(null) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoringBatteryOptimizations by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOptimizations = context.isIgnoringBatteryOptimizations()
                viewModel.ensureStream()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModel.ensureStream()
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.endpoint.ready) {
        if (state.endpoint.ready) viewModel.refreshSessions()
    }

    when {
        state.sessionID != null -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onSendImage = viewModel::sendImage,
            onSelectAgent = viewModel::selectAgent,
            onSelectModel = viewModel::selectModel,
            onInterrupt = viewModel::interrupt,
            onBack = viewModel::closeSession,
            onPermission = viewModel::replyPermission
        )

        state.directoryPicker != null -> DirectoryPickerScreen(
            picker = state.directoryPicker!!,
            onNavigate = viewModel::navigateDirectory,
            onSelect = viewModel::confirmDirectory,
            onRefresh = viewModel::openDirectoryPicker,
            onCancel = viewModel::closeDirectoryPicker
        )

        serverEditor != null -> PairingScreen(
            initial = serverEditor!!,
            error = state.error,
            onCancel = { serverEditor = null },
            onConnect = { endpoint ->
                viewModel.saveServer(endpoint)
                serverEditor = null
                serversOpen = false
            }
        )

        !state.endpoint.ready -> PairingScreen(
            initial = Endpoint(),
            error = state.error,
            onCancel = null,
            onConnect = { endpoint -> viewModel.saveServer(endpoint) }
        )

        serversOpen -> ServersScreen(
            servers = state.servers,
            active = state.endpoint,
            onSelect = { server ->
                viewModel.selectServer(server)
                serversOpen = false
            },
            onAdd = { serverEditor = Endpoint() },
            onEdit = { serverEditor = it },
            onDelete = viewModel::deleteServer,
            onClose = { serversOpen = false },
            batteryOptimized = !ignoringBatteryOptimizations,
            onRequestIgnoreBattery = { context.requestIgnoreBatteryOptimization() }
        )

        else -> SessionListScreen(
            state = state,
            onRefresh = viewModel::refreshSessions,
            onNew = viewModel::openDirectoryPicker,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
            onServers = { serversOpen = true }
        )
    }
}

private fun android.content.Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

private fun android.content.Context.requestIgnoreBatteryOptimization() {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName")
    )
    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    runCatching { startActivity(request) }
        .recoverCatching { startActivity(fallback) }
}
